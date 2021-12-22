/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*

/**
 * `X-Forwarded-*` headers support
 * See http://ktor.io/servers/features/forward-headers.html for details
 */
public object XForwardedHeaderSupport :
    ApplicationPlugin<ApplicationCallPipeline, XForwardedHeaderSupport.Config, XForwardedHeaderSupport.Config> {

    /**
     * Values of X-Forward-* headers. May contain multiple comma separated values
     */
    public data class XForwardedHeaderValues(
        public val protoHeader: String?,
        public val forHeader: String?,
        public val hostHeader: String?,
        public val httpsFlagHeader: String?,
        public val portHeader: String?
    )

    override val key: AttributeKey<Config> = AttributeKey("XForwardedHeaderSupport")

    override fun install(pipeline: ApplicationCallPipeline, configure: Config.() -> Unit): Config {
        val config = Config()
        configure(config)

        pipeline.intercept(ApplicationCallPipeline.Setup) {
            val strategy = config.xForwardedHeadersHandler
            val headers = XForwardedHeaderValues(
                protoHeader = config.protoHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                forHeader = config.forHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                hostHeader = config.hostHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                httpsFlagHeader = config.httpsFlagHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                portHeader = config.portHeaders.firstNotNullOfOrNull { call.request.headers[it] },
            )
            strategy.invoke(call.mutableOriginConnectionPoint, headers)
        }

        return config
    }

    /**
     * [XForwardedHeaderSupport] plugin's configuration
     */
    @Suppress("PublicApiImplicitType")
    public class Config {
        /**
         * Host name X-header names. Default are `X-Forwarded-Server` and `X-Forwarded-Host`
         */
        public val hostHeaders: ArrayList<String> =
            arrayListOf(HttpHeaders.XForwardedHost, HttpHeaders.XForwardedServer)

        /**
         * Protocol X-header names. Default are `X-Forwarded-Proto` and `X-Forwarded-Protocol`
         */
        public val protoHeaders: ArrayList<String> = arrayListOf(HttpHeaders.XForwardedProto, "X-Forwarded-Protocol")

        /**
         * `X-Forwarded-For` header names
         */
        public val forHeaders: ArrayList<String> = arrayListOf(HttpHeaders.XForwardedFor)

        /**
         * HTTPS/TLS flag header names. Default are `X-Forwarded-SSL` and `Front-End-Https`
         */
        public val httpsFlagHeaders: ArrayList<String> = arrayListOf("X-Forwarded-SSL", "Front-End-Https")

        /**
         * Port X-header names. Default is `X-Forwarded-Port`
         */
        public val portHeaders: ArrayList<String> = arrayListOf("X-Forwarded-Port")

        internal var xForwardedHeadersHandler: (MutableOriginConnectionPoint, XForwardedHeaderValues) -> Unit =
            { _, _ -> }

        init {
            useFirstValue()
        }

        /**
         * Custom logic to extract value from X-Forward-* headers when multiple values are present.
         * Users need to modify [MutableOriginConnectionPoint] based on headers from [XForwardedHeaderValues]
         */
        public fun extractValue(block: (MutableOriginConnectionPoint, XForwardedHeaderValues) -> Unit) {
            xForwardedHeadersHandler = block
        }

        /**
         * Takes first value from X-Forward-* headers when multiple values are present
         */
        public fun useFirstValue() {
            extractValue { connectionPoint, headers ->
                setValues(connectionPoint, headers) { it.firstOrNull()?.trim() }
            }
        }

        /**
         * Takes last value from X-Forward-* headers when multiple values are present
         */
        public fun useLastValue() {
            extractValue { connectionPoint, headers ->
                setValues(connectionPoint, headers) { it.lastOrNull()?.trim() }
            }
        }

        /**
         * Takes [proxiesCount]-before-last value from X-Forward-* headers when multiple values are present
         */
        public fun skipLastProxies(proxiesCount: Int) {
            extractValue { connectionPoint, headers ->
                setValues(connectionPoint, headers) { values ->
                    values.getOrElse(values.size - proxiesCount - 1) { values.lastOrNull() }?.trim()
                }
            }
        }

        /**
         * Takes first from the end value that is not in known [hosts]
         * from X-Forward-* headers when multiple values are present
         * */
        public fun skipKnownProxies(hosts: List<String>) {
            extractValue { connectionPoint, headers ->
                val forValues = headers.forHeader?.split(',')

                var proxiesCount = 0
                while (
                    hosts.lastIndex >= proxiesCount &&
                    forValues != null &&
                    forValues.lastIndex >= proxiesCount &&
                    hosts[hosts.size - proxiesCount - 1].trim() == forValues[forValues.size - proxiesCount - 1].trim()
                ) {
                    proxiesCount++
                }
                setValues(connectionPoint, headers) { values ->
                    values.getOrElse(values.size - proxiesCount - 1) { values.lastOrNull() }?.trim()
                }
            }
        }

        private fun setValues(
            connectionPoint: MutableOriginConnectionPoint,
            headers: XForwardedHeaderValues,
            extractValue: (List<String>) -> String?
        ) {
            val protoValues = headers.protoHeader?.split(',')
            val httpsFlagValues = headers.httpsFlagHeader?.split(',')
            val hostValues = headers.hostHeader?.split(',')
            val portValues = headers.portHeader?.split(',')
            val forValues = headers.forHeader?.split(',')

            protoValues?.let { values ->
                val scheme = extractValue(values) ?: return@let
                connectionPoint.scheme = scheme
                URLProtocol.byName[scheme]?.let { connectionPoint.port = it.defaultPort }
            }

            httpsFlagValues?.let { values ->
                val useHttps = extractValue(values).toBoolean()
                if (!useHttps) return@let

                connectionPoint.let { route ->
                    route.scheme = "https"
                    route.port = URLProtocol.HTTPS.defaultPort
                }
            }

            hostValues?.let { values ->
                val hostAndPort = extractValue(values) ?: return@let
                val host = hostAndPort.substringBefore(':')
                val port = hostAndPort.substringAfter(':', "")

                connectionPoint.host = host
                port.toIntOrNull()?.let {
                    connectionPoint.port = it
                } ?: URLProtocol.byName[connectionPoint.scheme]?.let {
                    connectionPoint.port = it.defaultPort
                }
            }

            portValues?.let { values ->
                val port = extractValue(values) ?: return@let
                connectionPoint.port = port.toInt()
            }

            forValues?.let { values ->
                val remoteHost = extractValue(values) ?: return@let
                if (remoteHost.isNotBlank()) {
                    connectionPoint.remoteHost = remoteHost
                }
            }
        }

        private fun String?.toBoolean() = this == "yes" || this == "true" || this == "on"
    }
}

/**
 * Forwarded header support. See RFC 7239 https://tools.ietf.org/html/rfc7239
 */
@Suppress("MemberVisibilityCanBePrivate")
public object ForwardedHeaderSupport : ApplicationPlugin<ApplicationCallPipeline, ForwardedHeaderSupport.Config, Unit> {
    /**
     * A key for application call attribute that is used to cache parsed header values
     */
    public val ForwardedParsedKey: AttributeKey<List<ForwardedHeaderValue>> = AttributeKey("ForwardedParsedKey")

    override val key: AttributeKey<Unit> = AttributeKey("ForwardedHeaderSupport")

    override fun install(pipeline: ApplicationCallPipeline, configure: Config.() -> Unit) {
        val config = Config().apply(configure)

        pipeline.intercept(ApplicationCallPipeline.Setup) {
            val forwarded = call.request.forwarded() ?: return@intercept
            call.attributes.put(ForwardedParsedKey, forwarded)
            config.forwardedHeadersHandler.invoke(call.mutableOriginConnectionPoint, forwarded)
        }
    }

    /**
     * Parsed forwarded header value. All fields are optional as proxy could provide different fields
     * @property host field value (optional)
     * @property by field value (optional)
     * @property forParam field value (optional)
     * @property proto field value (optional)
     * @property others contains custom field values passed by proxy
     */
    public data class ForwardedHeaderValue(
        val host: String?,
        val by: String?,
        val forParam: String?,
        val proto: String?,
        val others: Map<String, String>
    )

    // do we need it public?
    private fun ApplicationRequest.forwarded() =
        headers.getAll(HttpHeaders.Forwarded)
            ?.flatMap { it.split(',') }
            ?.flatMap { parseHeaderValue(";$it") }?.map {
                parseForwardedValue(it)
            }

    private fun parseForwardedValue(value: HeaderValue): ForwardedHeaderValue {
        val map = value.params.associateByTo(HashMap(), { it.name }, { it.value })

        return ForwardedHeaderValue(map.remove("host"), map.remove("by"), map.remove("for"), map.remove("proto"), map)
    }

    public class Config {
        internal var forwardedHeadersHandler: (MutableOriginConnectionPoint, List<ForwardedHeaderValue>) -> Unit =
            { _, _ -> }

        init {
            useFirstValue()
        }

        /**
         * Custom logic to extract value from Forward headers when multiple values are present.
         * Users need to modify [MutableOriginConnectionPoint] based on headers from [ForwardedHeaderValue]
         */
        public fun extractValue(block: (MutableOriginConnectionPoint, List<ForwardedHeaderValue>) -> Unit) {
            forwardedHeadersHandler = block
        }

        /**
         * Takes first value from Forward header when multiple values are present
         */
        public fun useFirstValue() {
            extractValue { connectionPoint, headers ->
                setValues(connectionPoint, headers.firstOrNull())
            }
        }

        /**
         * Takes last value from Forward header when multiple values are present
         */
        public fun useLastValue() {
            extractValue { connectionPoint, headers ->
                setValues(connectionPoint, headers.lastOrNull())
            }
        }

        /**
         * Takes [proxiesCount] before last value from Forward header when multiple values are present
         */
        public fun skipLastProxies(proxiesCount: Int) {
            extractValue { connectionPoint, headers ->
                setValues(connectionPoint, headers.getOrElse(headers.size - proxiesCount - 1) { headers.last() })
            }
        }

        /**
         * Takes first from the end value that is not in known [hosts]
         * from Forward header when multiple values are present
         */
        public fun skipKnownProxies(hosts: List<String>) {
            extractValue { connectionPoint, headers ->
                val forValues = headers.map { it.forParam }

                var proxiesCount = 0
                while (
                    hosts.lastIndex >= proxiesCount &&
                    forValues.lastIndex >= proxiesCount &&
                    hosts[hosts.size - proxiesCount - 1].trim() == forValues[forValues.size - proxiesCount - 1]?.trim()
                ) {
                    proxiesCount++
                }
                setValues(connectionPoint, headers.getOrElse(headers.size - proxiesCount - 1) { headers.last() })
            }
        }

        private fun setValues(
            connectionPoint: MutableOriginConnectionPoint,
            forward: ForwardedHeaderValue?
        ) {
            if (forward == null) {
                return
            }

            if (forward.proto != null) {
                val proto: String = forward.proto
                connectionPoint.scheme = proto
                URLProtocol.byName[proto]?.let { p ->
                    connectionPoint.port = p.defaultPort
                }
            }

            if (forward.forParam != null) {
                val remoteHost = forward.forParam.split(",").first().trim()
                if (remoteHost.isNotBlank()) {
                    connectionPoint.remoteHost = remoteHost
                }
            }

            if (forward.host != null) {
                val host = forward.host.substringBefore(':')
                val port = forward.host.substringAfter(':', "")

                connectionPoint.host = host
                port.toIntOrNull()?.let { connectionPoint.port = it }
                    ?: URLProtocol.byName[connectionPoint.scheme]?.let { connectionPoint.port = it.defaultPort }
            }
        }
    }
}
