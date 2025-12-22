package org.koitharu.kotatsu.parsers.site.en.kagane

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.text.SimpleDateFormat
import java.util.Locale

// Search response: /api/v1/search
internal class SearchResult(json: JSONObject) {
	val content: List<SearchBook> = json.getJSONArray("content").mapJSON { SearchBook(it) }
	val last: Boolean = json.optBoolean("last", true)

	fun hasNextPage(): Boolean = !last
}

internal class SearchBook(json: JSONObject) {
	val id: String = json.getString("id")
	val name: String = json.getString("name")
	val source: String = json.optString("source", "")
	val booksCount: Int = json.optInt("books_count", 0)
	val releaseDate: String? = json.getStringOrNull("release_date")

	fun toManga(apiUrl: String, parserSource: MangaParserSource, showSource: Boolean): Manga {
		val title = if (showSource && source.isNotEmpty()) "${name.trim()} [$source]" else name.trim()
		return Manga(
			id = generateMangaUid(id),
			title = title,
			altTitles = emptySet(),
			url = id,
			publicUrl = "$apiUrl/series/$id",
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = "$apiUrl/api/v1/series/$id/thumbnail",
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = parserSource,
		)
	}
}

// Details response: /api/v1/series/{id}
internal class SeriesDetails(json: JSONObject) {
	val source: String = json.optString("source", "")
	val authors: List<String> = parseStringArray(json, "authors")
	val status: String = json.optString("status", "")
	val summary: String? = json.getStringOrNull("summary")
	val genres: List<String> = parseStringArray(json, "genres")
	val alternateTitles: List<String> = parseAlternateTitles(json)

	private fun parseStringArray(json: JSONObject, key: String): List<String> {
		return try {
			val arr = json.optJSONArray(key) ?: return emptyList()
			(0 until arr.length()).mapNotNull { i ->
				val item = arr.get(i)
				when (item) {
					is String -> item
					is JSONObject -> item.optString("name") ?: item.optString("title")
					else -> item.toString()
				}
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	private fun parseAlternateTitles(json: JSONObject): List<String> {
		return try {
			val arr = json.optJSONArray("alternate_titles") ?: return emptyList()
			(0 until arr.length()).mapNotNull { i ->
				val item = arr.get(i)
				when (item) {
					is String -> item
					is JSONObject -> item.optString("title")
					else -> null
				}
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	fun toMangaState(): MangaState? = when (status.uppercase()) {
		"ONGOING" -> MangaState.ONGOING
		"ENDED", "COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		else -> null
	}
}

// Chapters response: /api/v1/books/{seriesId}
internal class ChapterList(json: JSONObject) {
	val content: List<ChapterBook> = json.getJSONArray("content").mapJSON { ChapterBook(it) }
}

internal class ChapterBook(json: JSONObject) {
	val id: String = json.getString("id")
	val seriesId: String = json.getString("series_id")
	val title: String = json.optString("title", "")
	val releaseDate: String? = json.getStringOrNull("release_date")
	val pagesCount: Int = json.optInt("pages_count", 0)
	val number: Float = json.optDouble("number_sort", 0.0).toFloat()

	companion object {
		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
	}

	fun toMangaChapter(parserSource: MangaParserSource): MangaChapter {
		val uploadDate = try {
			releaseDate?.let { dateFormat.parse(it)?.time } ?: 0L
		} catch (_: Exception) {
			0L
		}

		return MangaChapter(
			id = generateChapterUid(seriesId, id),
			title = title.ifEmpty { "Chapter ${number.toInt()}" },
			number = number,
			volume = 0,
			url = "$seriesId;$id;$pagesCount",
			scanlator = null,
			uploadDate = uploadDate,
			branch = null,
			source = parserSource,
		)
	}
}

// Challenge response: /api/v1/books/{seriesId}/file/{chapterId}
internal class ChallengeResponse(json: JSONObject) {
	val accessToken: String = json.getString("access_token")
	val cacheUrl: String = json.getString("cache_url")
	val pageMapping: Map<Int, String> = parsePageMapping(json)

	private fun parsePageMapping(json: JSONObject): Map<Int, String> {
		val map = mutableMapOf<Int, String>()
		val obj = json.optJSONObject("page_mapping") ?: return map
		for (key in obj.keys()) {
			try {
				map[key.toInt()] = obj.getString(key)
			} catch (_: Exception) {
				// Skip invalid entries
			}
		}
		return map
	}
}

internal class MetadataResult(json: JSONObject) {
	val genres: List<MetadataTag> = parseMetadataTags(json, "genres")
	val tags: List<MetadataTag> = parseMetadataTags(json, "tags")
	val sources: List<MetadataTag> = parseMetadataTags(json, "sources")

	private fun parseMetadataTags(json: JSONObject, key: String): List<MetadataTag> {
		return try {
			val arr = json.optJSONArray(key) ?: return emptyList()
			(0 until arr.length()).mapNotNull { i ->
				try {
					MetadataTag(arr.getJSONObject(i))
				} catch (_: Exception) {
					null
				}
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	fun getGenresList(): List<FilterData> = genres.map { FilterData(it.name, it.name) }

	fun getTagsList(): List<FilterData> = tags
		.sortedByDescending { it.count }
		.take(200)
		.map { FilterData(it.name, it.name.replaceFirstChar { c -> c.uppercase() }) }

	fun getSourcesList(): List<FilterData> = sources.map { FilterData(it.name, it.name) }
}

internal class MetadataTag(json: JSONObject) {
	val name: String = json.optString("name", "")
	val count: Int = json.optInt("count", 0)
}

internal data class FilterData(
	val id: String,
	val name: String,
)

internal class AlternateSeries(json: JSONObject) {
	val booksCount: Int = json.optInt("books_count", 0)
	val releaseDate: String? = json.getStringOrNull("release_date")
}

internal fun generateMangaUid(id: String): Long {
	return id.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL
}

internal fun generateChapterUid(seriesId: String, chapterId: String): Long {
	return ("$seriesId:$chapterId").hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL
}
