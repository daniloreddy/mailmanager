package org.danilorossi.mailmanager.spamassassin;

import jakarta.mail.Message;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.*;
import lombok.extern.java.Log;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.helpers.MailUtils;
import org.danilorossi.mailmanager.model.SpamAssassinConfig;

/**
 * Minimal SpamAssassin (spamd) client implementing core SPAMC/1.5 protocol parts.
 *
 * <p>Supported commands: - CHECK: fast boolean spam check + scores in "Spam:" header - SYMBOLS:
 * returns symbols/flags contributing to the score in the response body
 *
 * <p>Default port: 783
 *
 * <p>This class is lightweight and dependency-free (besides Lombok).
 */
@Log
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpamAssassinClient {

  static {
    LogConfigurator.configLog(log);
  }

  @Getter @NonNull @Builder.Default private String host = "127.0.0.1";

  @Getter @Builder.Default private int port = 783;

  @Getter @Builder.Default private int connectTimeoutMillis = 3000;

  @Getter @Builder.Default private int readTimeoutMillis = 5000;

  /**
   * Optional spamd User header (used for per-user configs on the server). If null, header is
   * omitted.
   */
  @Getter private String user;

  // --------------------------------------------------------------------------------------------
  // Public API
  // --------------------------------------------------------------------------------------------

  /** Perform a fast spam check (spamd CHECK). No body is returned; result is read from headers. */
  public CheckResult check(@NonNull final byte[] rfc822MessageBytes)
      throws IOException, ProtocolException {
    val resp = sendWithBody(Command.CHECK, rfc822MessageBytes);
    ensureOk(resp);
    // The SPAMD response should include a "Spam:" header like:
    // Spam: True ; 6.5 / 5.0
    val spamHeader = resp.getHeaders().get("spam");
    if (spamHeader == null) throw new ProtocolException("Missing 'Spam' header in CHECK response");
    val parsed = parseSpamHeader(spamHeader);
    return CheckResult.builder()
        .ok(true)
        .isSpam(parsed.isSpam())
        .score(parsed.getScore())
        .threshold(parsed.getThreshold())
        .rawResponse(resp.getRaw())
        .build();
  }

  /**
   * Utility to feed a String email (will be encoded UTF-8). Prefer byte[] of the original RFC822.
   */
  public CheckResult check(String rfc822Message) throws IOException, ProtocolException {
    return check(rfc822Message.getBytes(StandardCharsets.UTF_8));
  }

  /** Variante CHECK che accetta un jakarta.mail.Message. */
  public CheckResult check(@NonNull final Message message) throws IOException, ProtocolException {
    val bytes = MailUtils.toRfc822Bytes(message);
    return check(bytes);
  }

  /**
   * Request contributing symbols (spamd SYMBOLS). Returns full response including body. Body
   * commonly contains a space/comma-separated list of symbols.
   */
  public SymbolsResult symbols(@NonNull final byte[] rfc822MessageBytes)
      throws IOException, ProtocolException {
    val resp = sendWithBody(Command.SYMBOLS, rfc822MessageBytes);
    ensureOk(resp);
    val spamHeader = resp.getHeaders().get("spam");
    val score = spamHeader == null ? (ScoreLine) null : parseSpamHeader(spamHeader);

    // The body usually lists symbols; keep it raw to let the caller tokenize as preferred.
    return SymbolsResult.builder()
        .ok(true)
        .isSpam(score != null && score.isSpam())
        .score(score != null ? score.getScore() : null)
        .threshold(score != null ? score.getThreshold() : null)
        .symbolsRaw(resp.getBody() != null ? resp.getBody() : "")
        .rawResponse(resp.getRaw())
        .build();
  }

  /** Variante SYMBOLS che accetta un jakarta.mail.Message. */
  public SymbolsResult symbols(@NonNull final Message message)
      throws IOException, ProtocolException {
    val bytes = MailUtils.toRfc822Bytes(message);
    return symbols(bytes);
  }

  public SymbolsResult symbols(String rfc822Message) throws IOException, ProtocolException {
    return symbols(rfc822Message.getBytes(StandardCharsets.UTF_8));
  }

  // --------------------------------------------------------------------------------------------
  // Low-level protocol
  // --------------------------------------------------------------------------------------------

  private Response sendWithBody(@NonNull final Command cmd, @NonNull final byte[] body)
      throws IOException {
    @Cleanup val socket = new Socket();
    socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
    socket.setSoTimeout(readTimeoutMillis);

    @Cleanup val out = new BufferedOutputStream(socket.getOutputStream());
    @Cleanup val in = new BufferedInputStream(socket.getInputStream());

    // Write request line + headers
    val writer = new ByteArrayOutputStream(256);
    writer.write((cmd.getVerb() + " SPAMC/1.5\r\n").getBytes(StandardCharsets.US_ASCII));
    writer.write(("Content-length: " + body.length + "\r\n").getBytes(StandardCharsets.US_ASCII));

    if (!LangUtils.emptyString(user))
      writer.write(("User: " + user + "\r\n").getBytes(StandardCharsets.US_ASCII));
    // You could add: "Compress: zlib" + compressed body support, etc.

    writer.write("\r\n".getBytes(StandardCharsets.US_ASCII));

    // Flush headers + body
    out.write(writer.toByteArray());
    out.write(body);
    out.flush();

    // Parse response
    return readResponse(in);
  }

  private Response readResponse(@NonNull final InputStream in) throws IOException {
    // We read CRLF-delimited lines for the status line + headers.
    val headerLines = new ArrayList<String>(16);
    val statusLine = MailUtils.readLineCRLF(in);
    if (statusLine == null) {
      throw new EOFException("No status line from spamd");
    }
    // Headers until empty line
    String line;
    while ((line = MailUtils.readLineCRLF(in)) != null) {
      if (line.isEmpty()) break;
      headerLines.add(line);
    }

    // Parse status line: SPAMD/1.5 0 EX_OK
    val status = parseStatusLine(statusLine);

    // Parse headers (case-insensitive)
    val headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
    for (val h : headerLines) {
      val idx = h.indexOf(':');
      if (idx > 0) {
        val name = h.substring(0, idx).trim();
        val value = h.substring(idx + 1).trim();
        headers.put(name, value);
      }
    }

    // Optional body (if Content-length present)
    String body = null;
    val cl = headers.get("Content-length");
    if (cl != null) {
      val len = safeParseInt(cl, -1);
      if (len < 0) throw new ProtocolException("Invalid Content-length: " + cl);
      val buf = in.readNBytes(len);
      if (buf.length != len)
        throw new EOFException("Truncated body: expected " + len + " bytes, got " + buf.length);
      body = new String(buf, StandardCharsets.UTF_8);
    }

    // Build raw response (for diagnostics)
    val rawBuilder = new StringBuilder();
    rawBuilder.append(statusLine).append("\r\n");
    for (val h : headerLines) rawBuilder.append(h).append("\r\n");
    rawBuilder.append("\r\n");
    if (body != null) rawBuilder.append(body);

    return Response.builder()
        .protocol(status.getProtocol())
        .code(status.getCode())
        .statusText(status.getStatusText())
        .headers(Collections.unmodifiableMap(headers))
        .body(body)
        .raw(rawBuilder.toString())
        .build();
  }

  private static void ensureOk(@NonNull final Response r) throws ProtocolException {
    if (r.getCode() != 0) {
      throw new ProtocolException(
          "spamd returned non-OK: " + r.getCode() + " " + r.getStatusText());
    }
  }

  private static StatusLine parseStatusLine(@NonNull final String s) throws ProtocolException {
    // Expect: SPAMD/1.5 0 EX_OK
    val parts = s.split("\\s+", 3);
    if (parts.length < 3 || !parts[0].startsWith("SPAMD/")) {
      throw new ProtocolException("Invalid status line: " + s);
    }
    val code = safeParseInt(parts[1], Integer.MIN_VALUE);
    if (code == Integer.MIN_VALUE) {
      throw new ProtocolException("Invalid status code in line: " + s);
    }
    return new StatusLine(parts[0], code, parts[2]);
  }

  private static int safeParseInt(@NonNull String s, final int def) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return def;
    }
  }

  /** The "Spam:" header looks like: "True ; 6.3 / 5.0" OR "False ; 0.1 / 5.0" */
  private static ScoreLine parseSpamHeader(@NonNull final String headerValue)
      throws ProtocolException {
    // Normalize and split
    String v = headerValue.trim();
    // Example tokens: [True, ;, 6.3, /, 5.0] OR "True ; 6.3 / 5.0"
    // We'll be permissive: extract boolean, score, threshold via regex-free parsing.
    boolean isSpam;
    Double score = null;
    Double threshold = null;

    // Boolean (starts with True/False)
    if (v.regionMatches(true, 0, "true", 0, 4)) {
      isSpam = true;
      v = v.substring(4).trim();
    } else if (v.regionMatches(true, 0, "false", 0, 5)) {
      isSpam = false;
      v = v.substring(5).trim();
    } else {
      throw new ProtocolException("Cannot parse 'Spam' header boolean: " + headerValue);
    }

    // Remove optional leading semicolon
    if (v.startsWith(";")) v = v.substring(1).trim();

    // Expect "<score> / <threshold>"
    val slash = v.indexOf('/');
    if (slash < 0) {
      throw new ProtocolException("Cannot parse score/threshold in 'Spam' header: " + headerValue);
    }
    String left = v.substring(0, slash).trim();
    String right = v.substring(slash + 1).trim();

    // left might be "6.3" or "score=6.3" (be liberal)
    left = left.replace("score=", "").trim();
    right = right.replace("required=", "").trim();

    try {
      score = Double.parseDouble(left);
    } catch (Exception e) {
      throw new ProtocolException("Invalid score in 'Spam' header: " + headerValue);
    }
    try {
      threshold = Double.parseDouble(right);
    } catch (Exception e) {
      throw new ProtocolException("Invalid threshold in 'Spam' header: " + headerValue);
    }

    return new ScoreLine(isSpam, score, threshold);
  }

  // --------------------------------------------------------------------------------------------
  // Example usage
  // --------------------------------------------------------------------------------------------
  public static void main(String[] args) throws Exception {
    // Minimal demonstration (adjust host/port/user as needed)
    val client =
        SpamAssassinClient.builder()
            .host("127.0.0.1")
            .port(783)
            .user("mailmanager") // optional
            .connectTimeoutMillis(3000)
            .readTimeoutMillis(5000)
            .build();

    val sampleEmail =
        "From: test@example.com\r\n"
            + "To: you@example.com\r\n"
            + "Subject: Hello!\r\n"
            + "Date: Tue, 4 Sep 2025 10:00:00 +0200\r\n"
            + "Message-ID: <abc@example.com>\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "\r\n"
            + "Just a friendly test.\r\n";

    val check = client.check(sampleEmail);

    System.out.printf(
        "CHECK -> spam=%s score=%.3f threshold=%.3f%n",
        check.isSpam(), check.getScore(), check.getThreshold());

    val symbols = client.symbols(sampleEmail);
    System.out.println("SYMBOLS -> " + symbols.getSymbolsRaw());
  }

  public static SpamAssassinClient newFromConfig(@NonNull final SpamAssassinConfig config) {
    return SpamAssassinClient.builder()
        .host(config.getHost())
        .port(config.getPort())
        .user(config.getUser())
        .connectTimeoutMillis(config.getConnectTimeoutMillis())
        .readTimeoutMillis(config.getReadTimeoutMillis())
        .build();
  }
}
