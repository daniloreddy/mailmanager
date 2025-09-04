package org.danilorossi.mailmanager.model;

import lombok.*;
import lombok.experimental.Accessors;

/** Global SpamAssassin connection/config, salvata in JSON a parte. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class SpamAssassinConfig {

  @Builder.Default private boolean enabled = false;

  @Builder.Default private String host = "127.0.0.1";

  @Builder.Default private int port = 783;

  /** opzionale; se valorizzato, passato come header User a spamd */
  private String user;

  /** ms */
  @Builder.Default private int connectTimeoutMillis = 3000;

  /** ms */
  @Builder.Default private int readTimeoutMillis = 5000;
}
