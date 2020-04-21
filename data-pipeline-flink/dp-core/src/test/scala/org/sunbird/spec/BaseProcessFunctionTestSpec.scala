package org.sunbird.spec

import java.util

import com.google.gson.Gson
import com.typesafe.config.{Config, ConfigFactory}
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import net.manub.embeddedkafka.EmbeddedKafka._
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala._
import org.apache.flink.test.util.MiniClusterWithClientResource
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest.Matchers
import org.sunbird.dp.core.FlinkKafkaConnector
import org.sunbird.dp.util.FlinkUtil

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._


class BaseProcessFunctionTestSpec extends BaseSpec with Matchers {

  val flinkCluster = new MiniClusterWithClientResource(new MiniClusterResourceConfiguration.Builder()
    .setNumberSlotsPerTaskManager(1)
    .setNumberTaskManagers(1)
    .build)

  val config: Config = ConfigFactory.load("base-test.conf")
  val bsConfig = new BaseProcessTestConfig(config)
  val gson = new Gson()

  val kafkaConnector = new FlinkKafkaConnector(bsConfig)

  val EVENT_WITH_MESSAGE_ID: String =
    """
      |{"id":"sunbird.telemetry","ver":"3.0","ets":1529500243591,"params":{"msgid":"3fc11963-04e7-4251-83de-18e0dbb5a684",
      |"requesterId":"","did":"a3e487025d29f5b2cd599a8817ac16b8f3776a63","key":""},"events":[{"eid":"LOG","ets":1529499971358,
      |"ver":"3.0","mid":"LOG:5f3c177f90bd5833deade577cc28cbb6","actor":{"id":"159e93d1-da0c-4231-be94-e75b0c226d7c",
      |"type":"user"},"context":{"channel":"b00bc992ef25f1a9a8d63291e20efc8d","pdata":{"id":"local.sunbird.portal",
      |"ver":"0.0.1"},"env":"content-service","sid":"PCNHgbKZvh6Yis8F7BxiaJ1EGw0N3L9B","did":"cab2a0b55c79d12c8f0575d6397e5678",
      |"cdata":[],"rollup":{"l1":"ORG_001","l2":"0123673542904299520","l3":"0123673689120112640",
      |"l4":"b00bc992ef25f1a9a8d63291e20efc8d"}},"object":{},"tags":["b00bc992ef25f1a9a8d63291e20efc8d"],
      |"edata":{"type":"api_access","level":"INFO","message":"","params":[{"url":"/content/composite/v1/search"},
      |{"protocol":"https"},{"method":"POST"},{}]}}],"mid":"56c0c430-748b-11e8-ae77-cd19397ca6b0","syncts":1529500243955}
      |""".stripMargin

  val SHARE_EVENT: String =
    """
      |{"ver":"3.0","eid":"SHARE","ets":1577278681178,"actor":{"type":"User","id":"7c3ea1bb-4da1-48d0-9cc0-c4f150554149"},
      |"context":{"channel":"505c7c48ac6dc1edc9b08f21db5a571d","pdata":{"id":"prod.sunbird.desktop","pid":"sunbird.app",
      |"ver":"2.3.162"},"env":"app","sid":"82e41d87-e33f-4269-aeae-d56394985599","did":"1b17c32bad61eb9e33df281eecc727590d739b2b"},
      |"edata":{"dir":"In","type":"File","items":[{"origin":{"id":"1b17c32bad61eb9e33df281eecc727590d739b2b","type":"Device"},
      |"id":"do_312785709424099328114191","type":"CONTENT","ver":"1","params":[{"transfers":0,"size":21084308}]},
      |{"origin":{"id":"1b17c32bad61eb9e33df281eecc727590d739b2b","type":"Device"},"id":"do_31277435209002188818711",
      |"type":"CONTENT","ver":"18","params":[{"transfers":12,"size":"123"}]},{"origin":{"id":"1b17c32bad61eb9e33df281eecc727590d739b2b",
      |"type":"Device"},"id":"do_31278794857559654411554","type":"TextBook","ver":"1"}]},"object":{"id":"do_312528116260749312248818",
      |"type":"TextBook","version":"10","rollup":{}},"mid":"02ba33e5-15fe-4ec5-b32","syncts":1577278682630,request.timeout.ms = 50000
      |"@timestamp":"2019-12-25T12:58:02.630Z","type":"events"}
      |""".stripMargin

  val customKafkaConsumerProperties: Map[String, String] =
    Map[String, String]("auto.offset.reset" -> "earliest", "group.id" -> "test-event-schema-group")
  implicit val embeddedKafkaConfig: EmbeddedKafkaConfig =
    EmbeddedKafkaConfig (
      kafkaPort = 9093,
      zooKeeperPort = 2183,
      customConsumerProperties = customKafkaConsumerProperties
    )
  implicit val deserializer: StringDeserializer = new StringDeserializer()

  override def beforeAll(): Unit = {
    super.beforeAll()

    EmbeddedKafka.start()(embeddedKafkaConfig)
    try {
      createTestTopics(bsConfig.testTopics)
    } catch{
      case ex: Exception => {

      }
    }
    publishStringMessageToKafka(bsConfig.kafkaEventInputTopic, SHARE_EVENT)
    publishStringMessageToKafka(bsConfig.kafkaMapInputTopic, EVENT_WITH_MESSAGE_ID)
    publishStringMessageToKafka(bsConfig.kafkaStringInputTopic, SHARE_EVENT)

    flinkCluster.before()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    flinkCluster.after()
    EmbeddedKafka.stop()
  }

  def createTestTopics(topics: List[String]): Unit = {
    topics.foreach(createCustomTopic(_))
  }

  "Validation of SerDe" should "validate serialization and deserialization of Map, String and Event schema" in {

    implicit val env: StreamExecutionEnvironment = FlinkUtil.getExecutionContext(bsConfig)

    val eventStream =
      env.addSource(kafkaConnector.kafkaEventSource[Event](bsConfig.kafkaEventInputTopic), "event-schema-consumer")
      .process[Event](new TestEventStreamFunc(bsConfig)).name("TestTelemetryEventStream")

    eventStream.getSideOutput(bsConfig.eventOutputTag)
      .addSink(kafkaConnector.kafkaEventSink[Event](bsConfig.kafkaEventOutputTopic))
      .name("Event-Producer")

    val mapStream =
      env.addSource(kafkaConnector.kafkaMapSource(bsConfig.kafkaMapInputTopic), "map-event-consumer")
        .process(new TestMapStreamFunc(bsConfig)).name("TestMapEventStream")

    mapStream.getSideOutput(bsConfig.mapOutputTag)
      .addSink(kafkaConnector.kafkaMapSink(bsConfig.kafkaMapOutputTopic))
      .name("Map-Event-Producer")

    val stringStream =
      env.addSource(kafkaConnector.kafkaStringSource(bsConfig.kafkaStringInputTopic), "string-event-consumer")
      .process(new TestStringStreamFunc(bsConfig)).name("TestStringEventStream")

    stringStream.getSideOutput(bsConfig.stringOutputTag)
      .addSink(kafkaConnector.kafkaStringSink(bsConfig.kafkaStringOutputTopic))
      .name("String-Producer")

    Future {
      env.execute("TestSerDeFunctionality")
    }

    val events = consumeNumberMessagesFromTopics(
      Set(bsConfig.kafkaEventOutputTopic, bsConfig.kafkaMapOutputTopic, bsConfig.kafkaStringOutputTopic),
      number = 3, autoCommit = false,
      timeout = 30.seconds, resetTimeoutOnEachMessage = false)

    events.size should be(3)

    events(bsConfig.kafkaMapOutputTopic).size should be (0)
    events(bsConfig.kafkaEventOutputTopic).size should be (2)
    events(bsConfig.kafkaStringOutputTopic).size should be (1)

    val eventSchemaMessage = new Event(gson.fromJson(events(bsConfig.kafkaEventOutputTopic).head,
      new util.HashMap[String, AnyRef]().getClass))
    eventSchemaMessage.mid() should be ("02ba33e5-15fe-4ec5-b32")

    /*
    val mapSchemaMessage = new Event(gson.fromJson(events(bsConfig.kafkaMapOutputTopic).head,
      new util.HashMap[String, AnyRef]().getClass))
    mapSchemaMessage.mid() should be ("56c0c430-748b-11e8-ae77-cd19397ca6b0")*/

    val stringSchemaMessage = new Event(gson.fromJson(events(bsConfig.kafkaStringOutputTopic).head,
      new util.HashMap[String, AnyRef]().getClass))
    stringSchemaMessage.mid() should be ("02ba33e5-15fe-4ec5-b32")


  }

}
