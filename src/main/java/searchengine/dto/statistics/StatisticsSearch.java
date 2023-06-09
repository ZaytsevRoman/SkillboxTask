package searchengine.dto.statistics;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class StatisticsSearch {
    private String address;
    private String siteName;
    private String uri;
    private String title;
    private String snippet;
    private Float relevance;
}
