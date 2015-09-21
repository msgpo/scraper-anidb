/*
 * Copyright 2012 - 2015 Manuel Laggner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tinymediamanager.scraper.anidb;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.logging.LogManager;

import org.junit.BeforeClass;
import org.junit.Test;
import org.tinymediamanager.scraper.mediaprovider.ITvShowMetadataProvider;
import org.tinymediamanager.scraper.MediaCastMember;
import org.tinymediamanager.scraper.MediaMetadata;
import org.tinymediamanager.scraper.MediaScrapeOptions;
import org.tinymediamanager.scraper.MediaSearchOptions;
import org.tinymediamanager.scraper.MediaSearchOptions.SearchParam;
import org.tinymediamanager.scraper.MediaSearchResult;
import org.tinymediamanager.scraper.MediaType;

public class AniDBMetadataProviderTest {
  private static final String CRLF = "\n";

  @BeforeClass
  public static void setUp() {
    StringBuilder config = new StringBuilder("handlers = java.util.logging.ConsoleHandler\n");
    config.append(".level = ALL").append(CRLF);
    config.append("java.util.logging.ConsoleHandler.level = ALL").append(CRLF);
    // Only works with Java 7 or later
    config.append("java.util.logging.SimpleFormatter.format = [%1$tH:%1$tM:%1$tS %4$6s] %2$s - %5$s %6$s%n").append(CRLF);
    // Exclude http logging
    config.append("sun.net.www.protocol.http.HttpURLConnection.level = OFF").append(CRLF);
    InputStream ins = new ByteArrayInputStream(config.toString().getBytes());
    try {
      LogManager.getLogManager().readConfiguration(ins);
    }
    catch (IOException ignored) {
    }
  }

  @Test
  public void testSearch() {
    ITvShowMetadataProvider mp = new AniDBMetadataProvider();

    MediaSearchOptions options = new MediaSearchOptions(MediaType.TV_SHOW);
    options.set(SearchParam.QUERY, "Spider Riders");
    try {
      List<MediaSearchResult> results = mp.search(options);

      for (MediaSearchResult result : results) {
        System.out.println(result.getTitle() + " " + result.getId() + " " + result.getScore());
      }

      options.set(SearchParam.QUERY, "Spice and Wolf");
      results = mp.search(options);
    }
    catch (Exception e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  @Test
  public void testScrapeTvShow() {
    ITvShowMetadataProvider mp = new AniDBMetadataProvider();
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    MediaScrapeOptions options = new MediaScrapeOptions(MediaType.TV_SHOW);
    options.setId("anidb", "4242");
    try {
      MediaMetadata md = mp.getMetadata(options);
      assertEquals("2006-03-25", sdf.format(md.getDateValue(MediaMetadata.RELEASE_DATE)));
      assertEquals("2006", md.getStringValue(MediaMetadata.YEAR));
      assertEquals("Spider Riders", md.getStringValue(MediaMetadata.TITLE));
      assertEquals(
          "In this Earth, there exists unknown underground world, the Inner World. In the world, there are braves who fight with large spiders, and they are called Spider Riders. According to his grandfather`s diary, a boy, Hunter Steel is traveling around. Meanwhile he happens to enter the Inner World from a pyramid. There, the war between the insect squad that aims at conquest of the Inner World and Spider Riders continues. Oracle, the fairly of the Inner World, summons Hunter because he thinks Hunter will be the messiah of the world. However, the powers of Oracle are sealed by Mantid who is the rule of the Insecter. For the peace of the Inner World, he has to find four sealed keys of Oracle to retrieve Oracle`s power. Hunter asks Spider Shadow, which is a big spider chosen by Oracle, to become a member of Spider Riders to fight against enemies. Source: AnimeNfo Note: The first three episodes premiered in North America with a 2 month hiatus between episodes 3 and 4, after which the series continued without a break between seasons. Episodes 4-26 aired first in Japan.",
          md.getStringValue(MediaMetadata.PLOT));
      assertEquals(5.66d, md.getDoubleValue(MediaMetadata.RATING), 0.5);
      assertEquals(56, md.getIntegerValue(MediaMetadata.VOTE_COUNT), 5);
      assertEquals("http://img7.anidb.net/pics/anime/11059.jpg", md.getStringValue(MediaMetadata.POSTER_URL));
      assertEquals("Anime", md.getGenres().get(0).toString());

      // first actor
      MediaCastMember member = md.getCastMembers().get(0);
      assertEquals("Hunter Steele", member.getCharacter());
      assertEquals("Kumai Motoko", member.getName());
      assertEquals("http://img7.anidb.net/pics/anime/38865.jpg", member.getImageUrl());

      // second
      member = md.getCastMembers().get(1);
      assertEquals("Corona", member.getCharacter());
      assertEquals("Chiba Saeko", member.getName());
      assertEquals("http://img7.anidb.net/pics/anime/44706.jpg", member.getImageUrl());
    }
    catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }

  @Test
  public void testScrapeEpisode() {
    ITvShowMetadataProvider mp = new AniDBMetadataProvider();
    MediaScrapeOptions options = new MediaScrapeOptions(MediaType.TV_SHOW);
    options.setId("anidb", "4242");
    options.setId("episodeNr", "1");
    options.setId("seasonNr", "1");

    try {
      MediaMetadata md = mp.getMetadata(options);
    }
    catch (Exception e) {
      e.printStackTrace();
      fail();
    }
  }
}
