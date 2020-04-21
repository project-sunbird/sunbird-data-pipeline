package org.sunbird.dp.domain

import java.util

import com.google.gson.Gson
import com.google.gson.internal.LinkedTreeMap
import com.google.gson.reflect.TypeToken
import org.sunbird.dp.core.domain.Events

class Event(eventMap: util.Map[String, Any], partition: Integer) extends Events(eventMap) {


    private val jobName = "ContentCacheUpdater"

    def extractProperties(): Map[String, Any] = {

        val gson = new Gson()

        val transactionMap = telemetry.read[util.HashMap[String, Any]]("transactionData")

        if (null != transactionMap.getOrElse(null)) {

            val properties = telemetry.read[LinkedTreeMap[String, Any]]("transactionData.properties")
            var finalProperties = Map.empty[String,Any]
            properties.getOrElse(new LinkedTreeMap[String, Any]()).entrySet().forEach {
                entry =>
                    finalProperties = finalProperties + (entry.getKey ->
                        entry.getValue.asInstanceOf[LinkedTreeMap[String, Any]].getOrDefault("nv", None))
            }
            finalProperties
        }
            else {
            Map.empty[String, Any]
        }
    }


    def getNodeUniqueId(): String = {
        telemetry.read[String]("nodeUniqueId").get
    }
}
