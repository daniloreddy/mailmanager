package org.danilorossi.mailmanager.helpers;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import lombok.Cleanup;
import lombok.NonNull;
import lombok.val;

public final class MailTextExtractor {

  private static String cleanText(final String text) {
    if (text == null) return "";
    return text.replace('\u00A0', ' ') // nbsp → spazio normale
        .replaceAll("\\R+", " ") // qualunque newline → spazio
        .replaceAll("[\\t\\x0B\\f ]{2,}", " ") // compatta whitespace orizzontale ripetuto
        .trim();
  }

  private static Charset detectCharset(@NonNull final Part part) {
    try {
      val ct = part.getContentType();
      if (ct != null) {
        val contentType = new ContentType(ct);
        val cs = contentType.getParameter("charset");
        if (cs != null) {
          try {
            return Charset.forName(cs);
          } catch (Exception ignored) {
          }
        }
      }
    } catch (MessagingException ignored) {
    }
    return StandardCharsets.UTF_8; // default ragionevole
  }

  private static void extractPart(final Part part, @NonNull final StringBuilder out)
      throws Exception {

    if (part == null) return;

    // Salta allegati
    if (isAttachment(part)) return;

    if (part.isMimeType("text/plain")) {
      val txt = getTextPayload(part);
      if (!LangUtils.emptyString(txt)) out.append(cleanText(txt)).append("\n\n");
      return;
    }

    if (part.isMimeType("text/html")) {
      val html = getTextPayload(part);
      if (!LangUtils.emptyString(html)) out.append(htmlToPlainText(html)).append("\n\n");
      return;
    }

    if (part.isMimeType("multipart/alternative")) {
      // Scegli la versione migliore
      val content = part.getContent();
      if (content instanceof Multipart mp) {
        val best = pickFromAlternative(mp);
        if (!LangUtils.emptyString(best)) out.append(best).append("\n\n");
      }
      return;
    }

    if (part.isMimeType("multipart/*")) {
      val content = part.getContent();
      if (content instanceof Multipart mp) {
        val count = mp.getCount();
        for (int i = 0; i < count; i++) {
          val bp = mp.getBodyPart(i);
          try {
            extractPart(bp, out);
          } catch (Exception ignored) {
          }
        }
      }
      return;
    }

    if (part.isMimeType("message/rfc822")) {
      val content = part.getContent();
      if (content instanceof Message nested) {
        extractPart(nested, out);
      }
    }
    // Altri tipi testo meno comuni (opzionale)
    else if (part.isMimeType("text/*")) {
      val txt = getTextPayload(part);
      if (!LangUtils.emptyString(txt)) out.append(cleanText(txt)).append("\n\n");
    }
    // tutto il resto viene ignorato (immagini, pdf, ecc.)
  }

  public static String extractTextFromMessage(final Message msg) {
    val sb = new StringBuilder(2048);
    try {
      extractPart(msg, sb);
    } catch (Exception ignored) {
    }
    return sb.toString().trim();
  }

  private static String getTextPayload(@NonNull final Part part) {
    try {
      val content = part.getContent();
      if (content instanceof String s) return s;
      // In casi rari può essere InputStream

      try (InputStream is = part.getInputStream()) {
        return readAll(is, detectCharset(part));
      }
    } catch (Exception ignored) {
    }
    return "";
  }

  private static String htmlToPlainText(final String html) {
    // versione “semplice”: testo monolinea
    // se vuoi preservare i newline logici, usa la versione estesa proposta in precedenza
    org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
    return cleanText(doc.text());
  }

  private static boolean isAttachment(@NonNull final Part p) {
    try {
      val disp = p.getDisposition();
      if (!LangUtils.emptyString(disp) && disp.equalsIgnoreCase(Part.ATTACHMENT)) return true;
      // consideriamo attachment anche se c'è un filename
      if (!LangUtils.emptyString(p.getFileName())) return true;
    } catch (MessagingException ignored) {
    }
    return false;
  }

  private static String pickFromAlternative(@NonNull final Multipart mp) throws Exception {
    // 1) prova text/plain
    for (int i = 0; i < mp.getCount(); i++) {
      val bp = mp.getBodyPart(i);
      if (!isAttachment(bp) && bp.isMimeType("text/plain")) {
        return cleanText(getTextPayload(bp));
      }
    }
    // 2) fallback text/html
    for (int i = 0; i < mp.getCount(); i++) {
      val bp = mp.getBodyPart(i);
      if (!isAttachment(bp) && bp.isMimeType("text/html")) {
        return htmlToPlainText(getTextPayload(bp));
      }
    }
    // 3) ultima spiaggia: qualsiasi text/*
    for (int i = 0; i < mp.getCount(); i++) {
      val bp = mp.getBodyPart(i);
      if (!isAttachment(bp) && bp.isMimeType("text/*")) {
        return cleanText(getTextPayload(bp));
      }
    }
    return "";
  }

  private static String readAll(@NonNull final InputStream is, @NonNull final Charset cs)
      throws IOException {
    @Cleanup val bos = new ByteArrayOutputStream();
    val buf = new byte[8192];
    int r;
    while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
    return bos.toString(cs);
  }
}
