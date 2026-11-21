package com.music.laya.data.purplemusic

import com.music.laya.data.YtMusicRepository
import com.music.laya.data.innertube.StreamResolver
import com.music.laya.data.model.SearchFilter
import com.music.laya.data.model.SearchResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PurpleMusicClientTest {

    @Test
    fun normalizesVariousServerUrls() {
        assertEquals("https://music.monsite.com/", PurpleMusicClient.normalizeServerUrl("music.monsite.com"))
        assertEquals("https://music.monsite.com/", PurpleMusicClient.normalizeServerUrl("https://music.monsite.com/api.php"))
        assertEquals("https://music.monsite.com/sub/", PurpleMusicClient.normalizeServerUrl("https://music.monsite.com/sub/index.php"))
        assertEquals("http://localhost:51837/", PurpleMusicClient.normalizeServerUrl("localhost:51837"))
        assertEquals("http://192.168.1.50:8080/", PurpleMusicClient.normalizeServerUrl("192.168.1.50:8080"))
    }

    @Test
    fun endToEndPurpleMusicIntegrationWithRepositoryAndStreamResolver() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.requestUrl?.queryParameter("action").orEmpty()
                return when (action) {
                    "login" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"success","user_id":7,"username":"axolat","is_admin":true}""",
                    )
                    "list" -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "id": 101,
                            "filename": "track101.flac",
                            "title": "Midnight Violet",
                            "artist": "Axolat & Neon",
                            "album": "Purple Horizons",
                            "cover": "c101.webp",
                            "genre": "Synthwave, Electronic",
                            "play_count": 42,
                            "duration": 215,
                            "uploader_id": 7,
                            "like_count": 5,
                            "upload_date": "2026-11-20 18:00:00"
                          },
                          {
                            "id": 102,
                            "filename": "track102.mp3",
                            "title": "Cyber Dawn",
                            "artist": "Axolat",
                            "album": "Purple Horizons",
                            "cover": "c102.webp",
                            "genre": "Synthwave",
                            "play_count": 19,
                            "duration": 180,
                            "uploader_id": 7,
                            "like_count": 2
                          }
                        ]
                        """.trimIndent(),
                    )
                    "my_likes" -> MockResponse().setResponseCode(200).setBody(
                        """{"liked_ids":[101]}""",
                    )
                    "recommendations" -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "id": 101,
                            "filename": "track101.flac",
                            "title": "Midnight Violet",
                            "artist": "Axolat & Neon",
                            "album": "Purple Horizons",
                            "genre": "Synthwave",
                            "play_count": 42,
                            "duration": 215
                          }
                        ]
                        """.trimIndent(),
                    )
                    "daily_mixes" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"success","day":"2026-11-21","mixes":[]}""",
                    )
                    "server_top" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"success","tracks":[]}""",
                    )
                    "playlists" -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {
                            "id": 5,
                            "name": "Night Drive",
                            "creator_id": 7,
                            "creator": "axolat",
                            "song_ids": "101,102",
                            "cover": null,
                            "is_private": 0
                          }
                        ]
                        """.trimIndent(),
                    )
                    "get_playlist_tracks" -> MockResponse().setResponseCode(200).setBody(
                        """
                        [
                          {"id":101,"filename":"track101.flac","title":"Midnight Violet","artist":"Axolat & Neon","album":"Purple Horizons","genre":"Synthwave","play_count":42,"duration":215},
                          {"id":102,"filename":"track102.mp3","title":"Cyber Dawn","artist":"Axolat","album":"Purple Horizons","genre":"Synthwave","play_count":19,"duration":180}
                        ]
                        """.trimIndent(),
                    )
                    "albums" -> MockResponse().setResponseCode(200).setBody(
                        """
                        {"status":"success","albums":[{"id":1,"name":"Purple Horizons","artist":"Axolat","year":2026,"cover":"c101.webp","track_count":2,"total_duration":395}]}
                        """.trimIndent(),
                    )
                    "my_follows" -> MockResponse().setResponseCode(200).setBody(
                        """{"status":"success","artists":["Axolat"]}""",
                    )
                    "artist_bio" -> MockResponse().setResponseCode(200).setBody(
                        """{"extract":"Producteur synthwave.","url":null,"found":true,"cached":true}""",
                    )
                    else -> MockResponse().setResponseCode(200).setBody("""{"status":"success"}""")
                }
            }
        }
        server.start()
        try {
            val baseUrl = server.url("/").toString()
            val outcome = PurpleMusicClient.login(baseUrl, "axolat", "secret123").getOrThrow()
            assertEquals("axolat", outcome.account.username)
            assertTrue(PurpleMusicClient.isSignedIn)

            // 1. Home feed
            val home = YtMusicRepository.home().getOrThrow()
            assertTrue(home.shelves.isNotEmpty(), "Home shelves should be populated from PurpleMusic")

            // 2. Library page
            val lib = YtMusicRepository.library().getOrThrow()
            assertEquals(1, lib.likedSongs.size)
            assertEquals("101", lib.likedSongs.first().videoId)
            assertEquals("LOSSLESS", lib.likedSongs.first().sourceQuality)

            // 3. Playlist browse
            val playlistPage = YtMusicRepository.browseSongs("VL5").getOrThrow()
            assertEquals("Night Drive", playlistPage.header?.title)
            assertEquals(2, playlistPage.songs.size)
            assertEquals(true, playlistPage.owned)

            // 4. Artist page
            val artistPage = YtMusicRepository.artistPage("UCAxolat").getOrThrow()
            assertEquals("Axolat", artistPage.name)
            assertEquals(2, artistPage.songs.size)
            assertEquals("Producteur synthwave.", artistPage.description)
            assertEquals(true, artistPage.subscription?.subscribed)

            // 5. Stream resolution & download resolution
            val streamUrl = StreamResolver.resolve("101")
            assertEquals("${baseUrl}api.php?action=stream&q=101", streamUrl)

            val dlFlac = StreamResolver.resolveForDownload("101", Int.MAX_VALUE)
            assertEquals("flac", dlFlac.downloadExtension)
            assertEquals("audio/flac", dlFlac.downloadMimeType)

            val dlMp3 = StreamResolver.resolveForDownload("102", Int.MAX_VALUE)
            assertEquals("mp3", dlMp3.downloadExtension)
            assertEquals("audio/mpeg", dlMp3.downloadMimeType)

            // 6. Search fallback / filtering
            val songResults = YtMusicRepository.search("Midnight", SearchFilter.SONGS).getOrThrow()
            assertTrue(songResults.any { it is SearchResult.Track && it.song.videoId == "101" })
        } finally {
            PurpleMusicClient.signOut()
            server.shutdown()
        }
    }
}
