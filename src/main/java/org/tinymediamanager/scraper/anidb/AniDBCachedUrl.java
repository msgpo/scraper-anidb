package org.tinymediamanager.scraper.anidb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * @see <a href="https://wiki.anidb.net/w/HTTP_API_Definition">https://wiki.anidb.net/w/HTTP_API_Definition</a>
 *
 *      <quote> All users of this API should employ heavy local caching. Requesting the same dataset multiple times on a single day can get you
 *      banned. The same goes for request flooding. You should not request more than one page every two seconds. </quote>
 */
public class AniDBCachedUrl {
  static final private Charset UTF8          = Charset.forName("UTF-8");
  static final private long    ONE_DAY_IN_MS = 24 * 3600 * 1000;
  final private File           cacheDir;

  public AniDBCachedUrl(File cacheDir) {
    this.cacheDir = cacheDir;
    cacheDir.mkdirs();
  }

  private File getUrlCacheFile(String url) {
    return new File(cacheDir, "anidb." + md5(url.getBytes(UTF8)) + ".http.raw");
  }

  private long getMillisecondsSinceModified(File file) {
    if (file.exists()) {
      return System.currentTimeMillis() - file.lastModified();
    }
    else {
      return System.currentTimeMillis();
    }
  }

  // Every request gets in there
  private byte[] getRawContents(String url) throws Exception {
    File cache = getUrlCacheFile(url);
    // Request only once per day
    if (!cache.exists() && getMillisecondsSinceModified(cache) >= ONE_DAY_IN_MS * 2) {
      // Prevent doing more than one request per two seconds.
      waitToPreventFlood();
      byte[] data = readAndCloseInputStream(new URL(url).openStream());
      FileUtils.writeByteArrayToFile(cache, data);
      lastRequestEndedTime = System.currentTimeMillis();
    }
    return FileUtils.readFileToByteArray(cache);
  }

  public byte[] getByteArrayContents(String url) throws Exception {
    return getRawContents(url);
  }

  public String getStringContents(String url, Charset charset) throws Exception {
    return new String(getByteArrayContents(url), charset);
  }

  public String getStringContents(String url) throws Exception {
    return getStringContents(url, UTF8);
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

  static private byte[] readAndCloseInputStream(InputStream inputStream) throws IOException {
    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (InputStream is = inputStream) {
      IOUtils.copy(new GZIPInputStream(is), bos);
    }
    return bos.toByteArray();
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
