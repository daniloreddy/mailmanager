package org.danilorossi.mailmanager;

//State.java
import lombok.Data;

@Data // Aggiunge getter, setter, costruttori, etc.
public class State {
 private long uidValidity;
 private long lastProcessedUid;
}
