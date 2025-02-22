/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.zk

import kafka.api.LeaderAndIsr
import kafka.controller.LeaderIsrAndControllerEpoch
import kafka.coordinator.transaction.ProducerIdManager
import kafka.security.authorizer.AclAuthorizer
import kafka.security.authorizer.AclEntry.{WildcardHost, WildcardPrincipalString}
import kafka.server.{ConfigType, KafkaConfig, QuorumTestHarness, ZkAdminManager}
import kafka.utils.{PasswordEncoder, TestUtils}
import org.apache.kafka.common.acl.{AccessControlEntry, AclBinding, AclBindingFilter, AclOperation, AclPermissionType}
import org.apache.kafka.common.config.{ConfigResource, TopicConfig}
import org.apache.kafka.common.{TopicPartition, Uuid}
import org.apache.kafka.common.config.internals.QuotaConfigs
import org.apache.kafka.common.config.types.Password
import org.apache.kafka.common.errors.ControllerMovedException
import org.apache.kafka.common.metadata.{AccessControlEntryRecord, ConfigRecord, MetadataRecordType, ProducerIdsRecord}
import org.apache.kafka.common.quota.ClientQuotaEntity
import org.apache.kafka.common.resource.{PatternType, ResourcePattern, ResourcePatternFilter, ResourceType}
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.apache.kafka.common.utils.{SecurityUtils, Time}
import org.apache.kafka.metadata.{LeaderRecoveryState, PartitionRegistration}
import org.apache.kafka.metadata.migration.ZkMigrationLeadershipState
import org.apache.kafka.server.common.ApiMessageAndVersion
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue, fail}
import org.junit.jupiter.api.{BeforeEach, Test, TestInfo}

import java.util.{Properties, UUID}
import scala.collection.{Map, mutable}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/**
 * ZooKeeper integration tests that verify the interoperability of KafkaZkClient and ZkMigrationClient.
 */
class ZkMigrationClientTest extends QuorumTestHarness {

  private val InitialControllerEpoch: Int = 42
  private val InitialKRaftEpoch: Int = 0

  private var migrationClient: ZkMigrationClient = _

  private var migrationState: ZkMigrationLeadershipState = _

  private val SECRET = "secret"

  private val encoder: PasswordEncoder = {
    val encoderProps = new Properties()
    encoderProps.put(KafkaConfig.ZkConnectProp, "localhost:1234") // Get around the config validation
    encoderProps.put(KafkaConfig.PasswordEncoderSecretProp, SECRET) // Zk secret to encrypt the
    val encoderConfig = new KafkaConfig(encoderProps)
    PasswordEncoder.encrypting(encoderConfig.passwordEncoderSecret.get,
      encoderConfig.passwordEncoderKeyFactoryAlgorithm,
      encoderConfig.passwordEncoderCipherAlgorithm,
      encoderConfig.passwordEncoderKeyLength,
      encoderConfig.passwordEncoderIterations)
  }

  @BeforeEach
  override def setUp(testInfo: TestInfo): Unit = {
    super.setUp(testInfo)
    zkClient.createControllerEpochRaw(1)
    migrationClient = new ZkMigrationClient(zkClient, encoder)
    migrationState = initialMigrationState
    migrationState = migrationClient.getOrCreateMigrationRecoveryState(migrationState)
   }

  private def initialMigrationState: ZkMigrationLeadershipState = {
    val (epoch, stat) = zkClient.getControllerEpoch.get
    new ZkMigrationLeadershipState(3000, InitialControllerEpoch, 100, InitialKRaftEpoch, Time.SYSTEM.milliseconds(), -1, epoch, stat.getVersion)
  }

  @Test
  def testMigrateEmptyZk(): Unit = {
    val brokers = new java.util.ArrayList[Integer]()
    val batches = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()

    migrationClient.readAllMetadata(batch => batches.add(batch), brokerId => brokers.add(brokerId))
    assertEquals(0, brokers.size())
    assertEquals(0, batches.size())
  }

  @Test
  def testMigrationBrokerConfigs(): Unit = {
    val brokers = new java.util.ArrayList[Integer]()
    val batches = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()

    // Create some configs and persist in Zk.
    val props = new Properties()
    props.put(KafkaConfig.DefaultReplicationFactorProp, "1") // normal config
    props.put(KafkaConfig.SslKeystorePasswordProp, encoder.encode(new Password(SECRET))) // sensitive config
    zkClient.setOrCreateEntityConfigs(ConfigType.Broker, "1", props)

    migrationClient.readAllMetadata(batch => batches.add(batch), brokerId => brokers.add(brokerId))
    assertEquals(0, brokers.size())
    assertEquals(1, batches.size())
    assertEquals(2, batches.get(0).size)

    batches.get(0).forEach(record => {
      val message = record.message().asInstanceOf[ConfigRecord]
      val name = message.name
      val value = message.value

      assertTrue(props.containsKey(name))
      // If the config is senstive, compare it to the decoded value.
      if (name == KafkaConfig.SslKeystorePasswordProp) {
        assertEquals(SECRET, value)
      } else {
        assertEquals(props.getProperty(name), value)
      }
    })
  }

  @Test
  def testEmptyWrite(): Unit = {
    val (zkVersion, responses) = zkClient.retryMigrationRequestsUntilConnected(Seq(), migrationState)
    assertEquals(migrationState.migrationZkVersion(), zkVersion)
    assertTrue(responses.isEmpty)
  }

  @Test
  def testUpdateExistingPartitions(): Unit = {
    // Create a topic and partition state in ZK like KafkaController would
    val assignment = Map(
      new TopicPartition("test", 0) -> List(0, 1, 2),
      new TopicPartition("test", 1) -> List(1, 2, 3)
    )
    zkClient.createTopicAssignment("test", Some(Uuid.randomUuid()), assignment)

    val leaderAndIsrs = Map(
      new TopicPartition("test", 0) -> LeaderIsrAndControllerEpoch(
        new LeaderAndIsr(0, 5, List(0, 1, 2), LeaderRecoveryState.RECOVERED, -1), 1),
      new TopicPartition("test", 1) -> LeaderIsrAndControllerEpoch(
        new LeaderAndIsr(1, 5, List(1, 2, 3), LeaderRecoveryState.RECOVERED, -1), 1)
    )
    zkClient.createTopicPartitionStatesRaw(leaderAndIsrs, 0)

    // Now verify that we can update it with migration client
    assertEquals(0, migrationState.migrationZkVersion())

    val partitions = Map(
      0 -> new PartitionRegistration(Array(0, 1, 2), Array(1, 2), Array(), Array(), 1, LeaderRecoveryState.RECOVERED, 6, -1),
      1 -> new PartitionRegistration(Array(1, 2, 3), Array(3), Array(), Array(), 3, LeaderRecoveryState.RECOVERED, 7, -1)
    ).map { case (k, v) => Integer.valueOf(k) -> v }.asJava
    migrationState = migrationClient.updateTopicPartitions(Map("test" -> partitions).asJava, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    // Read back with Zk client
    val partition0 = zkClient.getTopicPartitionState(new TopicPartition("test", 0)).get.leaderAndIsr
    assertEquals(1, partition0.leader)
    assertEquals(6, partition0.leaderEpoch)
    assertEquals(List(1, 2), partition0.isr)

    val partition1 = zkClient.getTopicPartitionState(new TopicPartition("test", 1)).get.leaderAndIsr
    assertEquals(3, partition1.leader)
    assertEquals(7, partition1.leaderEpoch)
    assertEquals(List(3), partition1.isr)
  }

  @Test
  def testCreateNewPartitions(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    val partitions = Map(
      0 -> new PartitionRegistration(Array(0, 1, 2), Array(0, 1, 2), Array(), Array(), 0, LeaderRecoveryState.RECOVERED, 0, -1),
      1 -> new PartitionRegistration(Array(1, 2, 3), Array(1, 2, 3), Array(), Array(), 1, LeaderRecoveryState.RECOVERED, 0, -1)
    ).map { case (k, v) => Integer.valueOf(k) -> v }.asJava
    migrationState = migrationClient.createTopic("test", Uuid.randomUuid(), partitions, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    // Read back with Zk client
    val partition0 = zkClient.getTopicPartitionState(new TopicPartition("test", 0)).get.leaderAndIsr
    assertEquals(0, partition0.leader)
    assertEquals(0, partition0.leaderEpoch)
    assertEquals(List(0, 1, 2), partition0.isr)

    val partition1 = zkClient.getTopicPartitionState(new TopicPartition("test", 1)).get.leaderAndIsr
    assertEquals(1, partition1.leader)
    assertEquals(0, partition1.leaderEpoch)
    assertEquals(List(1, 2, 3), partition1.isr)
  }

  @Test
  def testIdempotentCreateTopics(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    val partitions = Map(
      0 -> new PartitionRegistration(Array(0, 1, 2), Array(0, 1, 2), Array(), Array(), 0, LeaderRecoveryState.RECOVERED, 0, -1),
      1 -> new PartitionRegistration(Array(1, 2, 3), Array(1, 2, 3), Array(), Array(), 1, LeaderRecoveryState.RECOVERED, 0, -1)
    ).map { case (k, v) => Integer.valueOf(k) -> v }.asJava
    val topicId = Uuid.randomUuid()
    migrationState = migrationClient.createTopic("test", topicId, partitions, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    migrationState = migrationClient.createTopic("test", topicId, partitions, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())
  }

  // Write Client Quotas using ZkMigrationClient and read them back using AdminZkClient
  private def writeClientQuotaAndVerify(migrationClient: ZkMigrationClient,
                                        adminZkClient: AdminZkClient,
                                        migrationState: ZkMigrationLeadershipState,
                                        entity: Map[String, String],
                                        quotas: Map[String, java.lang.Double],
                                        zkEntityType: String,
                                        zkEntityName: String): ZkMigrationLeadershipState = {
    val nextMigrationState = migrationClient.writeClientQuotas(
      entity.asJava,
      quotas.asJava,
      migrationState)
    val newProps = ZkAdminManager.clientQuotaPropsToDoubleMap(
      adminZkClient.fetchEntityConfig(zkEntityType, zkEntityName).asScala)
    assertEquals(quotas, newProps)
    nextMigrationState
  }


  @Test
  def testWriteExistingClientQuotas(): Unit = {
    val props = new Properties()
    props.put(QuotaConfigs.PRODUCER_BYTE_RATE_OVERRIDE_CONFIG, "100000")
    adminZkClient.changeConfigs(ConfigType.User, "user1", props)
    adminZkClient.changeConfigs(ConfigType.User, "user1/clients/clientA", props)

    assertEquals(0, migrationState.migrationZkVersion())
    migrationState = writeClientQuotaAndVerify(migrationClient, adminZkClient, migrationState,
      Map(ClientQuotaEntity.USER -> "user1"),
      Map(QuotaConfigs.PRODUCER_BYTE_RATE_OVERRIDE_CONFIG -> 20000.0),
      ConfigType.User, "user1")
    assertEquals(1, migrationState.migrationZkVersion())

    migrationState = writeClientQuotaAndVerify(migrationClient, adminZkClient, migrationState,
      Map(ClientQuotaEntity.USER -> "user1"),
      Map(QuotaConfigs.PRODUCER_BYTE_RATE_OVERRIDE_CONFIG -> 10000.0),
      ConfigType.User, "user1")
    assertEquals(2, migrationState.migrationZkVersion())

    migrationState = writeClientQuotaAndVerify(migrationClient, adminZkClient, migrationState,
      Map(ClientQuotaEntity.USER -> "user1"),
      Map.empty,
      ConfigType.User, "user1")
    assertEquals(3, migrationState.migrationZkVersion())

    migrationState = writeClientQuotaAndVerify(migrationClient, adminZkClient, migrationState,
      Map(ClientQuotaEntity.USER -> "user1"),
      Map(QuotaConfigs.CONSUMER_BYTE_RATE_OVERRIDE_CONFIG -> 100.0),
      ConfigType.User, "user1")
    assertEquals(4, migrationState.migrationZkVersion())
  }

  @Test
  def testWriteNewClientQuotas(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())
    migrationState = writeClientQuotaAndVerify(migrationClient, adminZkClient, migrationState,
      Map(ClientQuotaEntity.USER -> "user2"),
      Map(QuotaConfigs.PRODUCER_BYTE_RATE_OVERRIDE_CONFIG -> 20000.0, QuotaConfigs.CONSUMER_BYTE_RATE_OVERRIDE_CONFIG -> 100.0),
      ConfigType.User, "user2")

    assertEquals(1, migrationState.migrationZkVersion())

    migrationState = writeClientQuotaAndVerify(migrationClient, adminZkClient, migrationState,
      Map(ClientQuotaEntity.USER -> "user2", ClientQuotaEntity.CLIENT_ID -> "clientA"),
      Map(QuotaConfigs.PRODUCER_BYTE_RATE_OVERRIDE_CONFIG -> 10000.0, QuotaConfigs.CONSUMER_BYTE_RATE_OVERRIDE_CONFIG -> 200.0),
      ConfigType.User, "user2/clients/clientA")

    assertEquals(2, migrationState.migrationZkVersion())
  }

  @Test
  def testClaimAbsentController(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(1, migrationState.zkControllerEpochZkVersion())
  }

  @Test
  def testExistingKRaftControllerClaim(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(1, migrationState.zkControllerEpochZkVersion())

    // We don't require a KRaft controller to release the controller in ZK before another KRaft controller
    // can claim it. This is because KRaft leadership comes from Raft and we are just synchronizing it to ZK.
    var otherNodeState = ZkMigrationLeadershipState.EMPTY
      .withNewKRaftController(3001, 43)
      .withKRaftMetadataOffsetAndEpoch(100, 42);
    otherNodeState = migrationClient.claimControllerLeadership(otherNodeState)
    assertEquals(2, otherNodeState.zkControllerEpochZkVersion())
    assertEquals(3001, otherNodeState.kraftControllerId())
    assertEquals(43, otherNodeState.kraftControllerEpoch())
  }

  @Test
  def testNonIncreasingKRaftEpoch(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    migrationState = migrationState.withNewKRaftController(3001, InitialControllerEpoch)
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(1, migrationState.zkControllerEpochZkVersion())

    migrationState = migrationState.withNewKRaftController(3001, InitialControllerEpoch - 1)
    val t1 = assertThrows(classOf[ControllerMovedException], () => migrationClient.claimControllerLeadership(migrationState))
    assertEquals("Cannot register KRaft controller 3001 with epoch 41 as the current controller register in ZK has the same or newer epoch 42.", t1.getMessage)

    migrationState = migrationState.withNewKRaftController(3001, InitialControllerEpoch)
    val t2 = assertThrows(classOf[ControllerMovedException], () => migrationClient.claimControllerLeadership(migrationState))
    assertEquals("Cannot register KRaft controller 3001 with epoch 42 as the current controller register in ZK has the same or newer epoch 42.", t2.getMessage)

    migrationState = migrationState.withNewKRaftController(3001, 100)
    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(migrationState.kraftControllerEpoch(), 100)
    assertEquals(migrationState.kraftControllerId(), 3001)
  }

  @Test
  def testClaimAndReleaseExistingController(): Unit = {
    assertEquals(0, migrationState.migrationZkVersion())

    val (epoch, zkVersion) = zkClient.registerControllerAndIncrementControllerEpoch(100)
    assertEquals(epoch, 2)
    assertEquals(zkVersion, 1)

    migrationState = migrationClient.claimControllerLeadership(migrationState)
    assertEquals(2, migrationState.zkControllerEpochZkVersion())
    zkClient.getControllerEpoch match {
      case Some((zkEpoch, stat)) =>
        assertEquals(3, zkEpoch)
        assertEquals(2, stat.getVersion)
      case None => fail()
    }
    assertEquals(3000, zkClient.getControllerId.get)
    assertThrows(classOf[ControllerMovedException], () => zkClient.registerControllerAndIncrementControllerEpoch(100))

    migrationState = migrationClient.releaseControllerLeadership(migrationState)
    val (epoch1, zkVersion1) = zkClient.registerControllerAndIncrementControllerEpoch(100)
    assertEquals(epoch1, 4)
    assertEquals(zkVersion1, 3)
  }

  @Test
  def testReadAndWriteProducerId(): Unit = {
    def generateNextProducerIdWithZkAndRead(): Long = {
      // Generate a producer ID in ZK
      val manager = ProducerIdManager.zk(1, zkClient)
      manager.generateProducerId()

      val records = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()
      migrationClient.migrateProducerId(batch => records.add(batch))
      assertEquals(1, records.size())
      assertEquals(1, records.get(0).size())

      val record = records.get(0).get(0).message().asInstanceOf[ProducerIdsRecord]
      record.nextProducerId()
    }

    // Initialize with ZK ProducerIdManager
    assertEquals(0, generateNextProducerIdWithZkAndRead())

    // Update next producer ID via migration client
    migrationState = migrationClient.writeProducerId(6000, migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    // Switch back to ZK, it should provision the next block
    assertEquals(7000, generateNextProducerIdWithZkAndRead())
  }

  @Test
  def testMigrateTopicConfigs(): Unit = {
    val props = new Properties()
    props.put(TopicConfig.FLUSH_MS_CONFIG, "60000")
    props.put(TopicConfig.RETENTION_MS_CONFIG, "300000")
    adminZkClient.createTopicWithAssignment("test", props, Map(0 -> Seq(0, 1, 2), 1 -> Seq(1, 2, 0), 2 -> Seq(2, 0, 1)), usesTopicId = true)

    val brokers = new java.util.ArrayList[Integer]()
    val batches = new java.util.ArrayList[java.util.List[ApiMessageAndVersion]]()
    migrationClient.migrateTopics(batch => batches.add(batch), brokerId => brokers.add(brokerId))
    assertEquals(1, batches.size())
    val configs = batches.get(0)
      .asScala
      .map {_.message()}
      .filter(message => MetadataRecordType.fromId(message.apiKey()).equals(MetadataRecordType.CONFIG_RECORD))
      .map {_.asInstanceOf[ConfigRecord]}
      .toSeq
    assertEquals(2, configs.size)
    assertEquals(TopicConfig.FLUSH_MS_CONFIG, configs.head.name())
    assertEquals("60000", configs.head.value())
    assertEquals(TopicConfig.RETENTION_MS_CONFIG, configs.last.name())
    assertEquals("300000", configs.last.value())
  }

  @Test
  def testWriteNewTopicConfigs(): Unit = {
    migrationState = migrationClient.writeConfigs(new ConfigResource(ConfigResource.Type.TOPIC, "test"),
      java.util.Collections.singletonMap(TopicConfig.SEGMENT_MS_CONFIG, "100000"), migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    val newProps = zkClient.getEntityConfigs(ConfigType.Topic, "test")
    assertEquals(1, newProps.size())
    assertEquals("100000", newProps.getProperty(TopicConfig.SEGMENT_MS_CONFIG))
  }

  @Test
  def testWriteExistingTopicConfigs(): Unit = {
    val props = new Properties()
    props.put(TopicConfig.FLUSH_MS_CONFIG, "60000")
    props.put(TopicConfig.RETENTION_MS_CONFIG, "300000")
    zkClient.setOrCreateEntityConfigs(ConfigType.Topic, "test", props)

    migrationState = migrationClient.writeConfigs(new ConfigResource(ConfigResource.Type.TOPIC, "test"),
      java.util.Collections.singletonMap(TopicConfig.SEGMENT_MS_CONFIG, "100000"), migrationState)
    assertEquals(1, migrationState.migrationZkVersion())

    val newProps = zkClient.getEntityConfigs(ConfigType.Topic, "test")
    assertEquals(1, newProps.size())
    assertEquals("100000", newProps.getProperty(TopicConfig.SEGMENT_MS_CONFIG))
  }

  def migrateAclsAndVerify(authorizer: AclAuthorizer, acls: Seq[AclBinding]): Unit = {
    authorizer.createAcls(null, acls.asJava)
    val batches = new ArrayBuffer[mutable.Buffer[ApiMessageAndVersion]]()
    migrationClient.migrateAcls(batch => batches.append(batch.asScala))
    val records = batches.flatten.map(_.message().asInstanceOf[AccessControlEntryRecord])
    assertEquals(acls.size, records.size, "Expected one record for each ACLBinding")
  }

  def writeAclAndReadWithAuthorizer(
    authorizer: AclAuthorizer,
    resourcePattern: ResourcePattern,
    ace: AccessControlEntry,
    pred: Seq[AclBinding] => Boolean
  ): Seq[AclBinding] = {
    val resourceFilter = new AclBindingFilter(
      new ResourcePatternFilter(resourcePattern.resourceType(), resourcePattern.name(), resourcePattern.patternType()),
      AclBindingFilter.ANY.entryFilter()
    )
    migrationState = migrationClient.writeAddedAcls(resourcePattern, List(ace).asJava, migrationState)
    val (acls, ok) = TestUtils.computeUntilTrue(authorizer.acls(resourceFilter).asScala.toSeq)(pred)
    assertTrue(ok)
    acls
  }

  def deleteAclAndReadWithAuthorizer(
    authorizer: AclAuthorizer,
    resourcePattern: ResourcePattern,
    ace: AccessControlEntry,
    pred: Seq[AclBinding] => Boolean
  ): Seq[AclBinding] = {
    val resourceFilter = new AclBindingFilter(
      new ResourcePatternFilter(resourcePattern.resourceType(), resourcePattern.name(), resourcePattern.patternType()),
      AclBindingFilter.ANY.entryFilter()
    )
    migrationState = migrationClient.removeDeletedAcls(resourcePattern, List(ace).asJava, migrationState)
    val (acls, ok) = TestUtils.computeUntilTrue(authorizer.acls(resourceFilter).asScala.toSeq)(pred)
    assertTrue(ok)
    acls
  }

  @Test
  def testAclsMigrateAndDualWrite(): Unit = {
    val resource1 = new ResourcePattern(ResourceType.TOPIC, "foo-" + UUID.randomUUID(), PatternType.LITERAL)
    val resource2 = new ResourcePattern(ResourceType.TOPIC, "bar-" + UUID.randomUUID(), PatternType.LITERAL)
    val prefixedResource = new ResourcePattern(ResourceType.TOPIC, "bar-", PatternType.PREFIXED)
    val username = "alice"
    val principal = new KafkaPrincipal(KafkaPrincipal.USER_TYPE, username)
    val wildcardPrincipal = SecurityUtils.parseKafkaPrincipal(WildcardPrincipalString)

    val ace1 = new AccessControlEntry(principal.toString, WildcardHost, AclOperation.READ, AclPermissionType.ALLOW)
    val acl1 = new AclBinding(resource1, ace1)
    val ace2 = new AccessControlEntry(principal.toString, "192.168.0.1", AclOperation.WRITE, AclPermissionType.ALLOW)
    val acl2 = new AclBinding(resource1, ace2)
    val acl3 = new AclBinding(resource2, new AccessControlEntry(principal.toString, WildcardHost, AclOperation.DESCRIBE, AclPermissionType.ALLOW))
    val acl4 = new AclBinding(prefixedResource, new AccessControlEntry(wildcardPrincipal.toString, WildcardHost, AclOperation.READ, AclPermissionType.ALLOW))

    val authorizer = new AclAuthorizer()
    try {
      authorizer.configure(Map("zookeeper.connect" -> this.zkConnect).asJava)

      // Migrate ACLs
      migrateAclsAndVerify(authorizer, Seq(acl1, acl2, acl3, acl4))

      // Delete one of resource1's ACLs
      var resource1Acls = deleteAclAndReadWithAuthorizer(authorizer, resource1, ace2, acls => acls.size == 1)
      assertEquals(acl1, resource1Acls.head)

      // Delete the other ACL from resource1
      deleteAclAndReadWithAuthorizer(authorizer, resource1, ace1, acls => acls.isEmpty)

      // Add a new ACL for resource1
      val newAce1 = new AccessControlEntry(principal.toString, "10.0.0.1", AclOperation.WRITE, AclPermissionType.ALLOW)
      resource1Acls = writeAclAndReadWithAuthorizer(authorizer, resource1, newAce1, acls => acls.size == 1)
      assertEquals(newAce1, resource1Acls.head.entry())

      // Add a new ACL for resource2
      val newAce2 = new AccessControlEntry(principal.toString, "10.0.0.1", AclOperation.WRITE, AclPermissionType.ALLOW)
      val resource2Acls = writeAclAndReadWithAuthorizer(authorizer, resource2, newAce2, acls => acls.size == 2)
      assertEquals(acl3, resource2Acls.head)
      assertEquals(newAce2, resource2Acls.last.entry())
    } finally {
      authorizer.close()
    }
  }
}
