package com.ekina.domain.agent;

/**
 * Domain interface for Web Searching capabilities.
 * Implemented at the infrastructure layer using SearchAPI or Jsoup scrapers.
 */
public interface WebSearchService {

    /**
     * Executes a Google search query and returns the organic result summaries.
     */
    String search(String query);
}
