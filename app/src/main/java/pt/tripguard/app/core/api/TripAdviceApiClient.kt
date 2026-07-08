package pt.tripguard.app.core.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TripAdviceApiClient {
    fun requestAdvice(config: TripAdviceApiConfig, payload: JSONObject): RemoteAdvice {
        val connection = (URL(config.endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }

        val body = payload.toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { output -> output.write(body) }

        val status = connection.responseCode
        val responseText = if (status in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("API HTTP $status $error")
        }

        val json = JSONObject(responseText)
        val recommendations = json.optJSONArray("recommendations")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        } ?: emptyList()

        return RemoteAdvice(
            summary = json.optString("summary", "Sem resumo devolvido pela API."),
            riskLevel = json.optString("risk_level", "unknown"),
            recommendations = recommendations,
            receivedAtMs = System.currentTimeMillis()
        )
    }
}
