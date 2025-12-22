package org.koitharu.kotatsu.parsers.site.en.kagane

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val API_DOMAIN = "api.kagane.org"
private const val CACHE_DOMAIN = "yukine.kagane.org"

@MangaSourceParser("KAGANE", "Kagane", "en", ContentType.MANGA)
internal class Kagane(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KAGANE, pageSize = 35) {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("kagane.org")

	private val apiUrl: String
		get() = "https://$API_DOMAIN"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	private val apiHeaders: Headers by lazy {
		Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("Origin", "https://$domain")
			.add("Referer", "https://$domain/")
			.add("Accept", "application/json")
			.add("Accept-Encoding", "identity")  // disable compression to avoid gzip issues
			.build()
	}

	// token cache for image requests
	private var accessToken: String = ""
	private var cacheUrl: String = "https://$CACHE_DOMAIN"
	
	private val requestMutex = Mutex()
	private var lastRequestTime = 0L

	private suspend fun enforceRateLimit() {
		requestMutex.withLock {
			val now = System.currentTimeMillis()
			val waitTime = 1000L - (now - lastRequestTime)
			if (waitTime > 0) {
				delay(waitTime)
			}
			lastRequestTime = System.currentTimeMillis()
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		enforceRateLimit()
		val body = buildSearchBody(filter)

		val url = "$apiUrl/api/v1/search".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", (page - 1).toString())
			addQueryParameter("size", pageSize.toString())

			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("name", filter.query)
			}

			val sortParam = when (order) {
				SortOrder.UPDATED -> "updated_at,desc"
				SortOrder.POPULARITY -> "total_views,desc"
				SortOrder.ALPHABETICAL -> "series_name"
				else -> "updated_at,desc"
			}
			addQueryParameter("sort", sortParam)
			addQueryParameter("scanlations", "true")
		}.build()

		// use a fresh OkHttpClient without compression interceptors
		// using compression interceptors caused errors on api calls
		val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
		val request = okhttp3.Request.Builder()
			.url(url)
			.post(requestBody)
			.header("User-Agent", config[userAgentKey])
			.header("Origin", "https://$domain")
			.header("Referer", "https://$domain/")
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; charset=utf-8")
			.build()

		val simpleClient = okhttp3.OkHttpClient.Builder()
			.connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
			.build()

		val response = simpleClient.newCall(request).await()
		val responseBody = response.body.string()

		if (!response.isSuccessful) {
			throw IllegalStateException("API returned HTTP ${response.code}: ${responseBody.take(500)}")
		}

		val json = try {
			JSONObject(responseBody)
		} catch (e: Exception) {
			// debug error message
			throw IllegalStateException(
				"API returned non-JSON response (HTTP ${response.code}). First 500 chars: ${responseBody.take(500)}",
				e,
			)
		}

		val result = SearchResult(json)

		return result.content.map { book ->
			book.toManga(apiUrl, source, showSource = false)
		}
	}

	// TODO: fix broken search and genre filter
	private fun buildSearchBody(filter: MangaListFilter): JSONObject {
		val json = JSONObject()

		json.put("content_rating", JSONArray().apply {
			put("safe")
			put("suggestive")
			put("erotica")
			put("pornographic")
		})

		if (filter.tags.isNotEmpty()) {
			val inclusiveGenres = JSONObject().apply {
				put("values", JSONArray().apply {
					filter.tags.forEach { put(it.key) }
				})
				put("match_all", true)
			}
			json.put("inclusive_genres", inclusiveGenres)
		}

		return json
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val seriesId = manga.url
		val detailsUrl = "$apiUrl/api/v1/series/$seriesId"
		val chaptersUrl = "$apiUrl/api/v1/books/$seriesId"

		val detailsJson = webClient.httpGet(detailsUrl, apiHeaders).parseJson()
		val details = SeriesDetails(detailsJson)

		val chaptersJson = webClient.httpGet(chaptersUrl, apiHeaders).parseJson()
		val chapterList = ChapterList(chaptersJson)

		return manga.copy(
			description = buildDescription(details),
			authors = details.authors.toSet(),
			state = details.toMangaState(),
			tags = (listOf(details.source) + details.genres).filter { it.isNotEmpty() }.mapToSet { tag ->
				MangaTag(
					title = tag,
					key = tag.lowercase(),
					source = source,
				)
			},
			altTitles = details.alternateTitles.toSet(),
			chapters = chapterList.content.map { it.toMangaChapter(source) },
		)
	}

	private fun buildDescription(details: SeriesDetails): String {
		val desc = StringBuilder()
		if (!details.summary.isNullOrBlank()) {
			desc.append(details.summary).append("\n\n")
		}
		if (details.source.isNotEmpty()) {
			desc.append("Source: ").append(details.source).append("\n\n")
		}

		if (details.alternateTitles.isNotEmpty()) {
			desc.append("Associated Name(s):\n\n")
			details.alternateTitles.forEach {
				desc.append("• ").append(it).append("\n")
			}
		}
		return desc.toString()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.split(";")
		if (parts.size != 3) {
			throw IllegalArgumentException("Invalid chapter URL format: ${chapter.url}")
		}

		val (seriesId, chapterId, pageCountStr) = parts
		val pageCount = pageCountStr.toInt()

		// try to get DRM challenge and access token
		val challengeResponse = try {
			getChallengeResponse(seriesId, chapterId)
		} catch (e: Exception) {
			throw IllegalStateException(
				"Failed to get DRM challenge. Error: ${e.message}",
				e,
			)
		}

		accessToken = challengeResponse.accessToken
		cacheUrl = challengeResponse.cacheUrl

		return (0 until pageCount).map { page ->
			val pageUrl = "$cacheUrl/api/v1/books".toHttpUrl().newBuilder().apply {
				addPathSegment(seriesId)
				addPathSegment("file")
				addPathSegment(chapterId)
				addPathSegment(challengeResponse.pageMapping.getValue(page + 1))
				addQueryParameter("token", accessToken)
				addQueryParameter("index", (page + 1).toString())
			}.build().toString()

			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun getChallengeResponse(seriesId: String, chapterId: String): ChallengeResponse {
		enforceRateLimit()
		// generate key ID from series:chapter
		val keyIdBytes = sha256("$seriesId:$chapterId").sliceArray(0 until 16)

		// generate DRM challenge using WebView with DRM permissions
		val challenge = getDrmChallengeViaWebView(keyIdBytes, chapterId)

		val challengeUrl = "$apiUrl/api/v1/books/$seriesId/file/$chapterId".toHttpUrl()
		val challengeBody = JSONObject().apply {
			put("challenge", challenge)
		}

		val requestBody = challengeBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
		val request = okhttp3.Request.Builder()
			.url(challengeUrl)
			.post(requestBody)
			.header("User-Agent", config[userAgentKey])
			.header("Origin", "https://$domain")
			.header("Referer", "https://$domain/")
			.header("Accept", "application/json")
			.header("Content-Type", "application/json; charset=utf-8")
			.build()

		val simpleClient = okhttp3.OkHttpClient.Builder()
			.connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
			.build()

		val response = simpleClient.newCall(request).await()
		val responseBody = response.body.string()

		if (!response.isSuccessful) {
			throw IllegalStateException("Challenge API returned HTTP ${response.code}: ${responseBody.take(500)}")
		}

		val responseJson = JSONObject(responseBody)
		return ChallengeResponse(responseJson)
	}

	private suspend fun getDrmChallengeViaWebView(keyId: ByteArray, chapterId: String): String {
		val widevineCert = fetchWidevineCertificate()
		val psshBase64 = java.util.Base64.getEncoder().encodeToString(buildPsshBox(keyId))

		// note: kotatsu's evaluateJsWithDrm grants necessary permissions
		val script = """
			(function() {
				if (window.__kaganeChallenge) {
					return window.__kaganeChallenge;
				}
				if (window.__kaganeError) {
					return "ERROR:" + window.__kaganeError;
				}
				if (window.__kaganeWorking) {
					return null;
				}

				window.__kaganeWorking = true;

				function base64ToArrayBuffer(base64) {
					var raw = window.atob(base64);
					var rawLength = raw.length;
					var array = new Uint8Array(new ArrayBuffer(rawLength));
					for(var i = 0; i < rawLength; i++) {
						array[i] = raw.charCodeAt(i);
					}
					return array.buffer;
				}

				(async function() {
					try {
						// Check for Widevine support
						let keySystem = await navigator.requestMediaKeySystemAccess("com.widevine.alpha", [{
							initDataTypes: ["cenc"],
							audioCapabilities: [],
							videoCapabilities: [{
								contentType: 'video/mp4; codecs="avc1.42E01E"'
							}]
						}]);

						let mediaKeys = await keySystem.createMediaKeys();
						await mediaKeys.setServerCertificate(base64ToArrayBuffer("$widevineCert"));

						let session = mediaKeys.createSession();
						let challengePromise = new Promise((resolve, reject) => {
							session.addEventListener("message", (event) => {
								let m = new Uint8Array(event.message);
								let v = btoa(String.fromCharCode(...m));
								resolve(v);
							});
							session.addEventListener("error", (event) => {
								reject(new Error("DRM session error"));
							});
						});

						await session.generateRequest("cenc", base64ToArrayBuffer("$psshBase64"));
						window.__kaganeChallenge = await challengePromise;
					} catch (e) {
						window.__kaganeError = e.message;
					} finally {
						window.__kaganeWorking = false;
					}
				})();

				return null;
			})();
		""".trimIndent()

		var result = context.evaluateJsWithDrm("https://$domain", script, 15000L)
			?: throw IllegalStateException("Failed to get DRM challenge: WebView returned null")

		if (result.startsWith("\"") && result.endsWith("\"")) {
			result = result.substring(1, result.length - 1)
		}

		if (result.startsWith("ERROR:")) {
			throw IllegalStateException("DRM challenge failed: ${result.substringAfter("ERROR:")}")
		}

		return result
	}



	private suspend fun fetchWidevineCertificate(): String {
		val url = "$apiUrl/api/v1/static/bin.bin"
		val response = webClient.httpGet(url, apiHeaders)
		return java.util.Base64.getEncoder().encodeToString(response.use { it.body.bytes() })
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val url = "$apiUrl/api/v1/metadata"

		return try {
			val json = webClient.httpGet(url, apiHeaders).parseJson()
			val metadata = MetadataResult(json)

			MangaListFilterOptions(
				availableTags = metadata.getGenresList().mapToSet { data ->
					MangaTag(
						title = data.name,
						key = data.id,
						source = source,
					)
				},
			)
		} catch (_: Exception) {
			MangaListFilterOptions()
		}
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url

		// check if this is an image request with token
		if (!url.queryParameterNames.contains("token")) {
			return chain.proceed(request)
		}

		val seriesId = url.pathSegments.getOrNull(3) ?: return chain.proceed(request)
		val chapterId = url.pathSegments.getOrNull(5) ?: return chain.proceed(request)
		val pageIndex = url.queryParameter("index")?.toIntOrNull() ?: 1

		// handle token refresh on 401/507
		var response = chain.proceed(
			request.newBuilder()
				.url(url.newBuilder().setQueryParameter("token", accessToken).build())
				.build(),
		)

		if (response.code == 401 || response.code == 507) {
			response.close()
			// token expired, would need to refresh
			// TODO: implement token refresh
			throw IllegalStateException("Access token expired, please reload the chapter")
		}

		return KaganeImageProcessor.processImageResponse(
			response = response,
			seriesId = seriesId,
			chapterId = chapterId,
			pageIndex = pageIndex,
		)
	}
}
