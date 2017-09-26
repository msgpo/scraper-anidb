/*
 * Copyright 2012 - 2016 Manuel Laggner
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

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.scraper.MediaMetadata;
import org.tinymediamanager.scraper.MediaProviderInfo;
import org.tinymediamanager.scraper.MediaScrapeOptions;
import org.tinymediamanager.scraper.MediaSearchOptions;
import org.tinymediamanager.scraper.MediaSearchResult;
import org.tinymediamanager.scraper.UnsupportedMediaTypeException;
import org.tinymediamanager.scraper.entities.MediaArtwork;
import org.tinymediamanager.scraper.entities.MediaCastMember;
import org.tinymediamanager.scraper.entities.MediaCastMember.CastType;
import org.tinymediamanager.scraper.entities.MediaEpisode;
import org.tinymediamanager.scraper.entities.MediaGenres;
import org.tinymediamanager.scraper.entities.MediaType;
import org.tinymediamanager.scraper.http.CachedUrl;
import org.tinymediamanager.scraper.mediaprovider.IMediaArtworkProvider;
import org.tinymediamanager.scraper.mediaprovider.ITvShowMetadataProvider;
import org.tinymediamanager.scraper.util.RingBuffer;
import org.tinymediamanager.scraper.util.Similarity;
import org.tinymediamanager.scraper.util.StrgUtils;

import net.xeoh.plugins.base.annotations.PluginImplementation;

/**
 * The class AnimeDBMetadataProvider - a metadata provider for ANIME (AniDB) https://wiki.anidb.net/w/UDP_API_Definition
 * 
 * @author Manuel Laggner
 */
@PluginImplementation
public class AniDBMetadataProvider implements ITvShowMetadataProvider, IMediaArtworkProvider {
  private static final Logger              LOGGER            = LoggerFactory.getLogger(AniDBMetadataProvider.class);
  private static final String              IMAGE_SERVER      = "http://img7.anidb.net/pics/anime/";
  private static final RingBuffer<Long>    connectionCounter = new RingBuffer<>(2);
  private static MediaProviderInfo         providerInfo      = createMediaProviderInfo();

  private HashMap<String, List<AniDBShow>> showsForLookup    = new HashMap<>();

  private static MediaProviderInfo createMediaProviderInfo() {
    MediaProviderInfo providerInfo = new MediaProviderInfo("anidb", "aniDB",
        "<html><h3>aniDB</h3><br />AniDB stands for Anime DataBase. AniDB is a non-profit anime database that is open freely to the public.</html>",
        AniDBMetadataProvider.class.getResource("/anidb_net.png"));
    providerInfo.setVersion(AniDBMetadataProvider.class);
    return providerInfo;
  }

  public AniDBMetadataProvider() {
  }

  @Override
  public MediaProviderInfo getProviderInfo() {
    return providerInfo;
  }

  @Override
  public MediaMetadata getMetadata(MediaScrapeOptions mediaScrapeOptions) throws Exception {
    switch (mediaScrapeOptions.getType()) {
      case TV_SHOW:
        return getTvShowMetadata(mediaScrapeOptions);

      case TV_EPISODE:
        return getEpisodeMetadata(mediaScrapeOptions);

      default:
        throw new UnsupportedMediaTypeException(mediaScrapeOptions.getType());
    }
  }

  private MediaMetadata getTvShowMetadata(MediaScrapeOptions options) throws Exception {
    MediaMetadata md = new MediaMetadata(providerInfo.getId());
    String id = "";
    String langu = options.getLanguage().getLanguage();

    // id from result
    if (options.getResult() != null) {
      id = options.getResult().getId();
    }

    // do we have an id from the options?
    if (StringUtils.isEmpty(id)) {
      id = options.getId(providerInfo.getId());
    }

    if (StringUtils.isEmpty(id)) {
      return md;
    }

    // call API
    // http://api.anidb.net:9001/httpapi?request=anime&client=tinymediamanager&clientver=2&protover=1&aid=4242
    Document doc = null;
    try {
      CachedUrl cachedUrl = new CachedUrl("http://api.anidb.net:9001/httpapi?request=anime&client=tinymediamanager&clientver=2&protover=1&aid=" + id);
      if (!cachedUrl.isCached()) {
        trackConnections();
      }
      doc = Jsoup.parse(cachedUrl.getInputStream(), "UTF-8", "", Parser.xmlParser());
    }
    catch (Exception e) {
      LOGGER.error("failed to get TV show metadata: " + e.getMessage());
    }

    if (doc == null || doc.children().size() == 0) {
      return md;
    }

    md.setId(providerInfo.getId(), id);

    Element anime = doc.child(0);

    for (Element e : anime.children()) {
      if ("startdate".equalsIgnoreCase(e.tagName())) {
        try {
          Date date = StrgUtils.parseDate(e.text());
          md.setReleaseDate(date);

          Calendar calendar = Calendar.getInstance();
          calendar.setTime(date);
          md.setYear(calendar.get(Calendar.YEAR));
        }
        catch (ParseException ignored) {
        }
      }

      if ("titles".equalsIgnoreCase(e.tagName())) {
        parseTitle(md, langu, e);
      }

      if ("description".equalsIgnoreCase(e.tagName())) {
        md.setPlot(e.text());
      }

      if ("ratings".equalsIgnoreCase(e.tagName())) {
        getRating(md, e);
      }

      if ("picture".equalsIgnoreCase(e.tagName())) {
        // Poster
        MediaArtwork ma = new MediaArtwork(providerInfo.getId(), MediaArtwork.MediaArtworkType.POSTER);
        ma.setPreviewUrl(IMAGE_SERVER + e.text());
        ma.setDefaultUrl(IMAGE_SERVER + e.text());
        ma.setLanguage(options.getLanguage().getLanguage());
        md.addMediaArt(ma);
      }

      if ("characters".equalsIgnoreCase(e.tagName())) {
        getActors(md, e);
      }

    }

    // add static "Anime" genre
    md.addGenre(MediaGenres.ANIME);

    return md;
  }

  private void getActors(MediaMetadata md, Element e) {
    for (Element character : e.children()) {
      MediaCastMember member = new MediaCastMember(CastType.ACTOR);
      for (Element characterInfo : character.children()) {
        if ("name".equalsIgnoreCase(characterInfo.tagName())) {
          member.setCharacter(characterInfo.text());
        }
        if ("seiyuu".equalsIgnoreCase(characterInfo.tagName())) {
          member.setName(characterInfo.text());
          String image = characterInfo.attr("picture");
          if (StringUtils.isNotBlank(image)) {
            member.setImageUrl("http://img7.anidb.net/pics/anime/" + image);
          }
        }
      }
      md.addCastMember(member);
    }
  }

  private void getRating(MediaMetadata md, Element e) {
    for (Element rating : e.children()) {
      if ("temporary".equalsIgnoreCase(rating.tagName())) {
        try {
          md.setRating(Float.parseFloat(rating.text()));
          md.setVoteCount(Integer.parseInt(rating.attr("count")));
          break;
        }
        catch (NumberFormatException ignored) {
        }
      }
    }
  }

  private void parseTitle(MediaMetadata md, String langu, Element e) {
    String titleEN = "";
    String titleScraperLangu = "";
    String titleFirst = "";
    for (Element title : e.children()) {
      // store first title if neither the requested one nor the english one
      // available
      if (StringUtils.isBlank(titleFirst)) {
        titleFirst = title.text();
      }

      // store the english one for fallback
      if ("en".equalsIgnoreCase(title.attr("xml:lang"))) {
        titleEN = title.text();
      }

      // search for the requested one
      if (langu.equalsIgnoreCase(title.attr("xml:lang"))) {
        titleScraperLangu = title.text();
      }

    }

    if (StringUtils.isNotBlank(titleScraperLangu)) {
      md.setTitle(titleScraperLangu);
    }
    else if (StringUtils.isNotBlank(titleEN)) {
      md.setTitle(titleEN);
    }
    else {
      md.setTitle(titleFirst);
    }
  }

  public MediaMetadata getEpisodeMetadata(MediaScrapeOptions options) throws Exception {
    MediaMetadata md = new MediaMetadata(providerInfo.getId());

    String id = "";
    String langu = options.getLanguage().getLanguage();

    // id from result
    if (options.getResult() != null) {
      id = options.getResult().getId();
    }

    // do we have an id from the options?
    if (StringUtils.isEmpty(id)) {
      id = options.getId(providerInfo.getId());
    }

    if (StringUtils.isEmpty(id)) {
      return md;
    }

    // get episode number and season number
    int seasonNr = -1;
    int episodeNr = -1;

    try {
      seasonNr = Integer.parseInt(options.getId(MediaMetadata.SEASON_NR));
      episodeNr = Integer.parseInt(options.getId(MediaMetadata.EPISODE_NR));
    }
    catch (Exception e) {
      LOGGER.warn("error parsing season/episode number");
    }

    if (seasonNr == -1 || episodeNr == -1) {
      return md;
    }

    Document doc = null;
    try {
      CachedUrl url = new CachedUrl("http://api.anidb.net:9001/httpapi?request=anime&client=tinymediamanager&clientver=2&protover=1&aid=" + id);
      if (!url.isCached()) {
        trackConnections();
      }
      doc = Jsoup.parse(url.getInputStream(), "UTF-8", "", Parser.xmlParser());
    }
    catch (Exception e) {
      LOGGER.error("failed to get episode metadata: " + e.getMessage());
    }

    if (doc == null || doc.children().size() == 0) {
      return md;
    }

    md.setId(providerInfo.getId(), id);

    List<Episode> episodes = parseEpisodes(doc);
    Episode episode = null;

    // filter out the episode
    for (Episode ep : episodes) {
      if (ep.season == seasonNr && ep.episode == episodeNr) {
        episode = ep;
        break;
      }
    }

    if (episode == null) {
      return md;
    }

    md.setSeasonNumber(episode.season);
    md.setEpisodeNumber(episode.episode);
    String title = episode.titles.get(langu);
    if (StringUtils.isBlank(title)) {
      title = episode.titles.get("en");
    }
    if (StringUtils.isBlank(title)) {
      title = episode.titles.get("x-jat");
    }
    md.setTitle(title);
    md.setPlot(episode.summary);
    md.setRating(episode.rating);
    try {
      md.setReleaseDate(StrgUtils.parseDate(episode.airdate));
    }
    catch (ParseException ignored) {
    }
    md.setRuntime(episode.runtime);
    md.setId(providerInfo.getId(), episode.id);

    return md;
  }

  private List<Episode> parseEpisodes(Document doc) {
    List<Episode> episodes = new ArrayList<>();

    Element anime = doc.child(0);
    Element eps = null;
    // find the "episodes" child
    for (Element e : anime.children()) {
      if ("episodes".equalsIgnoreCase(e.tagName())) {
        eps = e;
        break;
      }
    }

    if (eps == null) {
      return episodes;
    }

    for (Element e : eps.children()) {
      // filter out the desired episode
      if ("episode".equals(e.tagName())) {
        Episode episode = new Episode();
        try {
          episode.id = Integer.parseInt(e.attr("id"));
        }
        catch (NumberFormatException ignored) {
        }
        for (Element episodeInfo : e.children()) {
          if ("epno".equalsIgnoreCase(episodeInfo.tagName())) {
            try {
              episode.episode = Integer.parseInt(episodeInfo.text());

              // looks like anidb is storing anything in a single season, so put
              // 1 to season, if type = 1
              if ("1".equals(episodeInfo.attr("type"))) {
                episode.season = 1;
              }
              else {
                // else - we see them as "specials"
                episode.season = 0;
              }

            }
            catch (NumberFormatException ignored) {
            }
            continue;
          }

          if ("length".equalsIgnoreCase(episodeInfo.tagName())) {
            try {
              episode.runtime = Integer.parseInt(episodeInfo.text());
            }
            catch (NumberFormatException ignored) {
            }
            continue;
          }

          if ("airdate".equalsIgnoreCase(episodeInfo.tagName())) {
            episode.airdate = episodeInfo.text();
            continue;
          }

          if ("rating".equalsIgnoreCase(episodeInfo.tagName())) {
            try {
              episode.rating = Float.parseFloat(episodeInfo.text());
            }
            catch (NumberFormatException ignored) {
            }
            continue;
          }

          if ("title".equalsIgnoreCase(episodeInfo.tagName())) {
            try {
              episode.titles.put(episodeInfo.attr("xml:lang").toLowerCase(Locale.ROOT), episodeInfo.text());
            }
            catch (Exception ignored) {
            }
            continue;
          }

          if ("summary".equalsIgnoreCase(episodeInfo.tagName())) {
            episode.summary = episodeInfo.text();
            continue;
          }
        }
        episodes.add(episode);
      }
    }

    return episodes;
  }

  @Override
  public List<MediaSearchResult> search(MediaSearchOptions options) throws Exception {
    LOGGER.debug("search() " + options.toString());

    if (options.getMediaType() != MediaType.TV_SHOW) {
      throw new UnsupportedMediaTypeException(options.getMediaType());
    }

    synchronized (AniDBMetadataProvider.class) {
      // first run: build up the anime name list
      if (showsForLookup.size() == 0) {
        buildTitleHashMap();
      }
    }

    List<MediaSearchResult> results = new ArrayList<>();

    // detect the string to search
    String searchString = "";
    if (StringUtils.isNotEmpty(options.getQuery())) {
      searchString = options.getQuery();
    }

    // return an empty search result if no query provided
    if (StringUtils.isEmpty(searchString)) {
      return results;
    }

    List<Integer> foundIds = new ArrayList<>();
    for (Entry<String, List<AniDBShow>> entry : showsForLookup.entrySet()) {
      String title = entry.getKey();
      float score = Similarity.compareStrings(title, searchString);
      if (score > 0.4) {
        for (AniDBShow show : entry.getValue()) {
          if (!foundIds.contains(show.aniDbId)) {
            MediaSearchResult result = new MediaSearchResult(providerInfo.getId(), MediaType.TV_SHOW);
            result.setId(String.valueOf(show.aniDbId));
            result.setTitle(show.title);
            results.add(result);
            result.setScore(score);
            foundIds.add(show.aniDbId);
          }
        }
      }
    }

    // sort
    Collections.sort(results);
    Collections.reverse(results);

    return results;
  }

  @Override
  public List<MediaEpisode> getEpisodeList(MediaScrapeOptions options) throws Exception {
    List<MediaEpisode> episodes = new ArrayList<>();

    String id = "";
    String langu = options.getLanguage().getLanguage();

    // id from result
    if (options.getResult() != null) {
      id = options.getResult().getId();
    }

    // do we have an id from the options?
    if (StringUtils.isEmpty(id)) {
      id = options.getId(providerInfo.getId());
    }

    if (StringUtils.isEmpty(id)) {
      return episodes;
    }

    Document doc = null;
    try {
      CachedUrl url = new CachedUrl("http://api.anidb.net:9001/httpapi?request=anime&client=tinymediamanager&clientver=2&protover=1&aid=" + id);
      if (!url.isCached()) {
        trackConnections();
      }
      doc = Jsoup.parse(url.getInputStream(), "UTF-8", "", Parser.xmlParser());
    }
    catch (Exception e) {
      LOGGER.error("error getting episode list: " + e.getMessage());
    }

    if (doc == null || doc.children().size() == 0) {
      return episodes;
    }

    // filter out the episode
    for (Episode ep : parseEpisodes(doc)) {
      MediaEpisode episode = new MediaEpisode(getProviderInfo().getId());
      episode.title = ep.titles.get(langu);
      episode.season = ep.season;
      episode.episode = ep.episode;
      if (StringUtils.isBlank(episode.title)) {
        episode.title = ep.titles.get("en");
      }
      if (StringUtils.isBlank(episode.title)) {
        episode.title = ep.titles.get("x-jat");
      }

      episode.plot = ep.summary;
      episode.rating = ep.rating;
      episode.firstAired = ep.airdate;
      episode.ids.put(providerInfo.getId(), ep.id);
    }

    return episodes;
  }

  /*
   * build up the hashmap for a fast title search
   */
  private void buildTitleHashMap() {
    // <aid>|<type>|<language>|<title>
    // type: 1=primary title (one per anime), 2=synonyms (multiple per anime),
    // 3=shorttitles (multiple per anime), 4=official title (one per
    // language)
    Pattern pattern = Pattern.compile("^(?!#)(\\d+)[|](\\d)[|]([\\w-]+)[|](.+)$");
    Scanner scanner = null;
    try {
      CachedUrl animeList = new CachedUrl("http://anidb.net/api/anime-titles.dat.gz");
      if (!animeList.isCached()) {
        trackConnections();
      }
      // scanner = new Scanner(new GZIPInputStream(animeList.getInputStream()));
      // DecompressingHttpClient is decompressing the gz from animedb due to
      // wrong http-server configuration
      scanner = new Scanner(animeList.getInputStream(), "UTF-8");
      while (scanner.hasNextLine()) {
        Matcher matcher = pattern.matcher(scanner.nextLine());

        if (matcher.matches()) {
          AniDBShow show = new AniDBShow();
          show.aniDbId = Integer.parseInt(matcher.group(1));
          show.language = matcher.group(3);
          show.title = matcher.group(4);

          List<AniDBShow> shows = showsForLookup.get(show.title);
          if (shows == null) {
            shows = new ArrayList<>();
            showsForLookup.put(show.title, shows);
          }

          shows.add(show);
        }
      }
    }
    catch (InterruptedException e) {
      LOGGER.warn("interrupted image download");
    }
    catch (IOException e) {
      LOGGER.error("error getting AniDB index");
    }
    finally {
      if (scanner != null) {
        try {
          scanner.close();
        }
        catch (Exception ignored) {
        }
      }
    }
  }

  /*
   * Track connections and throttle if needed.
   */
  private synchronized static void trackConnections() {
    Long currentTime = System.currentTimeMillis();
    if (connectionCounter.count() == connectionCounter.maxSize()) {
      Long oldestConnection = connectionCounter.getTailItem();
      if (oldestConnection > (currentTime - 4000)) {
        LOGGER.debug("connection limit reached, throttling " + connectionCounter);
        try {
          Thread.sleep(5000 - (currentTime - oldestConnection));
        }
        catch (InterruptedException e) {
          LOGGER.warn(e.getMessage());
        }
      }
    }

    currentTime = System.currentTimeMillis();
    connectionCounter.add(currentTime);
  }

  @Override
  public List<MediaArtwork> getArtwork(MediaScrapeOptions options) throws Exception {
    List<MediaArtwork> artwork = new ArrayList<>();
    String id = "";

    // check if there is a metadata containing an id
    if (options.getMetadata() != null) {
      id = (String) options.getMetadata().getId(providerInfo.getId());
    }

    // get the id from the options
    if (StringUtils.isEmpty(id)) {
      id = options.getId(providerInfo.getId());
    }

    if (StringUtils.isEmpty(id)) {
      return artwork;
    }

    switch (options.getArtworkType()) {
      // AniDB only offers Poster
      case ALL:
      case POSTER:
        MediaMetadata md;
        try {
          md = getTvShowMetadata(options);
        }
        catch (Exception e) {
          return artwork;
        }

        artwork.addAll(md.getMediaArt(options.getArtworkType()));

        break;

      default:
        return artwork;
    }

    return artwork;
  }

  /****************************************************************************
   * helper class to buffer search results from AniDB
   ****************************************************************************/
  private static class AniDBShow {
    int    aniDbId;
    String language;
    String title;
  }

  /****************************************************************************
   * helper class for episode extraction
   ****************************************************************************/
  private static class Episode {
    int                     id      = -1;
    int                     episode = -1;
    int                     season  = -1;
    int                     runtime = 0;
    String                  airdate = "";
    float                   rating  = 0;
    String                  summary = "";
    HashMap<String, String> titles  = new HashMap<>();
  }
}
