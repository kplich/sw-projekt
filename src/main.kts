#!/usr/bin/env kotlin

import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

val googleApiKey: String = System.getenv("GOOGLE_API_KEY")
val lcpRegex = Regex("\"LARGEST_CONTENTFUL_PAINT_MS\": \\{\\s+\"percentile\": (\\d+),")

fun String.run(workingDir: File): String {
    return try {
        val parts = this.split("\\s".toRegex())
        val proc = ProcessBuilder(*parts.toTypedArray())
            .directory(workingDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        proc.waitFor(15, TimeUnit.SECONDS)
        proc.inputStream.bufferedReader().readText()
    }
    catch(e: IOException) {
        e.printStackTrace()
        ""
    }
}

fun String.withoutProtocolAndWww(): String {
    return this.removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
}

fun getCurlCommand(url: String): String {
    val timingTemplate = "%{time_namelookup};%{time_connect};%{time_pretransfer};%{time_starttransfer};%{time_total}"
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:80.0) Gecko/20100101 Firefox/80.0"
    // -w - write-out format
    // -o - output location
    // -s - no progress meter
    // -A - set user agent
    val logFileName = Path.of("..", "archive", "${url.withoutProtocolAndWww()}.txt").toString()
    return "curl -w \"$timingTemplate\" -o $logFileName -s -A \"$userAgent\" $url"
}

fun getLcpCommand(url: String): String {
    val urlEncodedUrl = URLEncoder.encode(url, "utf-8")
    val googleApi =
        "https://pagespeedonline.googleapis.com/pagespeedonline/v5/runPagespeed?category=PERFORMANCE&strategy=DESKTOP&url=$urlEncodedUrl&key=$googleApiKey"
    return "curl --header \"Accept: application/json\" -s $googleApi"
}

fun curlResultsToDataLine(results: String, now: Instant): String {
    val resultsSplit = results.replace(",", ".").split(";")
    val cumulativeTimings = resultsSplit.map { (it.toFloat() * 1000).toLong() }

    // count differences between timings
    val noncumulativeTimings = ArrayList(cumulativeTimings.mapIndexed { index, fl ->
        return@mapIndexed if (index != 0) {
            fl - cumulativeTimings[index - 1]
        } else {
            fl
        }
    })
    return now.toString() + "," + noncumulativeTimings.joinToString(",") + "\n"
}

fun getCurlDataFile(url: String): File {
    val curlDataHeader = "timestamp,name_lookup_time,connection_time,handshake_time,sever_processing_time,content_transfer_time"
    val strippedSiteUrl = url.withoutProtocolAndWww()

    val dataFile: File = Path.of("..", "curl-data", "$strippedSiteUrl.csv").toFile()
    if (!dataFile.exists()) {
        dataFile.writeText(curlDataHeader + "\n")
    }

    return dataFile
}

fun lcpResultsToDataLine(results: String, now: Instant): String? {
    val matchResult = lcpRegex.find(results)

    return if(matchResult != null) {
        now.toString() + "," + matchResult.groupValues[1] + "\n"
    }
    else {
        null
    }
}

fun getLcpDataFile(url: String): File {
    val lcpDataHeader = "timestamp,lcp_time"
    val strippedSiteUrl = url.withoutProtocolAndWww()
    val dataFile: File = Path.of("..", "lcp-data", "$strippedSiteUrl.csv").toFile()

    if (!dataFile.exists()) {
        dataFile.writeText(lcpDataHeader + "\n")
    }

    return dataFile
}

val here = File(".")
val sitesTested = arrayOf(
    "https://allegro.pl",
    "https://www.x-kom.pl",
    "https://www.morele.net",
    "https://www.komputronik.pl",
    "https://www.aliexpress.com",
    "https://www.amazon.cn"
)

sitesTested.forEach { url ->
    val now: Instant = Instant.now()

    val curlResults = getCurlCommand(url).run(here)
    println(curlResults)

    getCurlDataFile(url).appendText(curlResultsToDataLine(curlResults, now))

    val lcpResults = getLcpCommand(url).run(here)

    val lcpDataLine = lcpResultsToDataLine(lcpResults, now)
    if(lcpDataLine != null) {
        getLcpDataFile(url).appendText(lcpDataLine)
    }
}