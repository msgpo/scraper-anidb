package org.tinymediamanager.scraper.anidb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tinymediamanager.scraper.http.Url;

/**
 * @see <a href="https://wiki.anidb.net/w/HTTP_API_Definition">https://wiki.anidb.net/w/HTTP_API_Definition</a>
 *
 *      <quote> All users of this API should employ heavy local caching. Requesting the same dataset multiple times on a single day can get you
 *      banned. The same goes for request flooding. You should not request more than one page every two seconds. </quote>
 */
public class AniDBCachedUrl {
  private static final Logger LOGGER        = LoggerFactory.getLogger(AniDBCachedUrl.class);
  static final private long   ONE_DAY_IN_MS = 24 * 3600 * 1000;
  private static final Path   CACHE_DIR     = Paths.get("cache");

  public AniDBCachedUrl() {
  }

  private Path getCachedFilename(String url) {
    return CACHE_DIR.resolve(Paths.get("anidb." + md5(url.getBytes(StandardCharsets.UTF_8)) + ".http.raw"));
  }

  private long getMillisecondsSinceModified(Path file) {
    if (Files.exists(file)) {
      try {
        return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
      }
      catch (IOException e) {
        LOGGER.error("Could not get file time!", e);
        return System.currentTimeMillis();
      }
    }
    else {
      return System.currentTimeMillis();
    }
  }

  public static String readFileToString(Path file) throws IOException {
    byte[] fileArray = Files.readAllBytes(file);
    return new String(fileArray, StandardCharsets.UTF_8);
  }

  /**
   * returns cached file, or downloads fresh
   * 
   * @param url
   * @return
   * @throws Exception
   */
  public Path getCachedFile(String url) throws Exception {
    Path cache = getCachedFilename(url);
    // Request only once per day
    if (!Files.exists(cache) && getMillisecondsSinceModified(cache) >= ONE_DAY_IN_MS * 2) {
      // Prevent doing more than one request per two seconds.
      waitToPreventFlood();
      Url u = new Url(url);
      boolean ok = u.download(cache);
      if (!ok) {
        LOGGER.error("Error downloading cached file!");
      }
      lastRequestEndedTime = System.currentTimeMillis();
    }
    return cache;
  }

  public String getStringContents(String url) throws Exception {
    return readFileToString(getCachedFile(url));
  }

  public Document getXmlContents(String url) throws Exception {
    return Jsoup.parse(getStringContents(url));
  }

  // Tools
  private long lastRequestEndedTime = 0L;

  private void waitToPreventFlood() throws InterruptedException {
    long timeSinceLastRequest = System.currentTimeMillis() - lastRequestEndedTime;
    if (timeSinceLastRequest < 2000) {
      Thread.sleep(2000 - timeSinceLastRequest);
    }
  }

  static private String md5(byte[] data) {
    try {
      final MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(data);
      byte[] digest = md.digest();
      final StringBuilder sb = new StringBuilder();
      for (byte b : digest)
        sb.append(String.format("%02x", b & 0xff));
      return sb.toString();
    }
    catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
      throw new RuntimeException("ERROR getting MD5", e);
    }
  }
}
