package org.nodescope.android.core.network

import org.nodescope.android.R
import org.nodescope.android.core.design.UiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** User-facing text for known analyzer failures, with a caller-specific fallback. */
internal fun Throwable.analyzerErrorText(fallback: Int): UiText = when (this) {
    is InvalidAnalyzerHost -> UiText.Resource(messageRes)
    is HttpFailure -> UiText.Resource(R.string.analyzer_error_http, status)
    is BeaconApiException.Server -> UiText.Resource(R.string.beacon_error_status, status)
    is BeaconApiException.UnrecognizedVersion -> UiText.Resource(R.string.beacon_error_unknown_version)
    is BeaconApiException.UnsupportedVersion -> UiText.Resource(R.string.beacon_error_unsupported_version, version)
    is BeaconApiException.RateLimited -> UiText.Resource(R.string.beacon_error_rate_limited,
        DateTimeFormatter.ofPattern("h:mm:ss a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(untilMillis)))
    is BeaconApiException.MalformedPage -> UiText.Resource(R.string.beacon_error_malformed_page)
    is BeaconApiException.InvalidResponse -> UiText.Resource(R.string.analyzer_error_invalid_response)
    is AnalyzerPagingException.InvalidCursor -> UiText.Resource(R.string.analyzer_error_page_cursor)
    is AnalyzerPagingException.InvalidPageSize -> UiText.Resource(R.string.analyzer_error_page_size)
    is AnalyzerSourceNotReadyException -> UiText.Resource(R.string.app_checking_analyzer_source)
    is BeaconNodeUnavailableException -> UiText.Resource(R.string.beacon_error_node_unavailable)
    is BeaconChannelUnavailableException -> UiText.Resource(R.string.beacon_error_channel_unavailable)
    is NodeCheckAmbiguousKeyException -> UiText.Resource(R.string.node_check_ambiguous_key)
    is NotCached -> UiText.Resource(R.string.analyzer_error_not_cached)
    is BeaconUnsupportedException -> capability.beaconUiExplanation
    else -> UiText.Resource(fallback)
}
