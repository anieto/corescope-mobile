package org.nodescope.android.feature.map

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Authenticate every CARTO style/tile/font request, including URLs nested in styles. */
class CartoRequests(private val key: String, private val packageName: String, private val signingSha1: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(authenticate(chain.request()))

    internal fun authenticate(request: Request): Request {
        val host = request.url.host
        if (request.url.scheme != "https" || !(host == "basemaps.cartocdn.com" || host.endsWith(".basemaps.cartocdn.com"))) return request
        return request.newBuilder()
            .url(request.url.newBuilder().setQueryParameter("key", key).build())
            .header("X-Android-Package", packageName)
            .header("X-Android-Cert", signingSha1)
            .build()
    }
}
