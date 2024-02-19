package io.github.slackhappy.poolsdemo;

import java.util.Map;

public class QueryRequest {
  public String query;
  public final String user;
  public QueryRequest(String query, String user) {
    this.query = query;
    this.user = user;
  }
  public boolean queryIsSynonymsElible() {
    return true;
  }
  public void addSynonyms(Map<String, String> synonyms) {
    query += " OR " + String.join(" OR ", synonyms.values());
  }
}
