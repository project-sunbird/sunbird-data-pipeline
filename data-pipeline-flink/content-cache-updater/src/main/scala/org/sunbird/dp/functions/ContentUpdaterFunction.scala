package org.sunbird.dp.functions

import java.text.SimpleDateFormat

import com.google.gson.Gson
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.dp.cache.RedisConnect
import org.sunbird.dp.core.{BaseProcessFunction, DataCache, Metrics}
import org.sunbird.dp.domain.Event
import org.sunbird.dp.task.ContentCacheUpdaterConfig

class ContentUpdaterFunction(config: ContentCacheUpdaterConfig)(implicit val mapTypeInfo: TypeInformation[Event])
  extends BaseProcessFunction[Event, Event](config) {

    private[this] val logger = LoggerFactory.getLogger(classOf[ContentUpdaterFunction])

    private var dataCache: DataCache = _

    override def metricsList(): List[String] = {
        List(config.contentCacheHit)
    }

    override def open(parameters: Configuration): Unit = {
        super.open(parameters)
        dataCache = new DataCache(config, new RedisConnect(config), config.contentStore, config.contentListFields)
        dataCache.init()
    }

    override def close(): Unit = {
        super.close()
        dataCache.close()
    }

    override def processElement(event: Event, context: ProcessFunction[Event, Event]#Context, metrics: Metrics): Unit = {
        val gson = new Gson()
        import collection.JavaConverters._
        val nodeUniqueId = event.getNodeUniqueId()
        val redisData = dataCache.getWithRetry(nodeUniqueId)
        val finalProperties = event.extractProperties().filter(p => null != p._2)
        val newProperties = finalProperties.map(map => {
            if (config.contentDateFields.contains(map._1))
                (map._1, new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(map._2.toString).getTime)
            else if (config.contentListFields.contains(map._1))
                (map._1, List(map._2.toString))
            else
                (map._1, map._2)
        })
        redisData ++= newProperties.asInstanceOf[Map[String, AnyRef]]
        if (!redisData.isEmpty) {
            dataCache.setWithRetry(event.getNodeUniqueId(), gson.toJson(redisData.asJava))
            metrics.incCounter(config.contentCacheHit)
        }
    }
}
