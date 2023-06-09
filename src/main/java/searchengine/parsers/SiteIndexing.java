package searchengine.parsers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import searchengine.config.ConnectionConfiguration;
import searchengine.config.SitesList;
import searchengine.dto.statistics.StatisticsIndex;
import searchengine.dto.statistics.StatisticsLemma;
import searchengine.dto.statistics.StatisticsPage;
import searchengine.model.*;
import searchengine.model.Index;
import searchengine.model.Lemma;
import searchengine.repository.IndexRepository;
import searchengine.repository.LemmaRepository;
import searchengine.repository.PageRepository;
import searchengine.repository.SiteRepository;

import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;

@RequiredArgsConstructor
@Slf4j
public class SiteIndexing implements Runnable {
    private final PageRepository pageRepository;
    private final SiteRepository siteRepository;
    private final LemmaRepository lemmaRepository;
    private static final int coreAmount = Runtime.getRuntime().availableProcessors();
    private final IndexRepository indexRepository;
    private final String url;
    private final SitesList sitesList;
    private final ConnectionConfiguration connectionConfiguration;

    @Override
    public void run() {
        if (siteRepository.findSiteByUrl(url) != null) {
            log.info("Удаление данных сайта - " + url);
            deleteSiteData();
        }
        log.info("Индексация сайта - " + url + " " + getSiteName());
        saveSiteData();
        try {
            List<StatisticsPage> statisticsPageList = getStatisticsPageList();
            savePageList(statisticsPageList);
            saveLemmaList();
            saveIndexList();
        } catch (InterruptedException e) {
            log.error("Индексация остановлена - " + url);
            saveSiteIndexingError();
        }
    }

    private void deleteSiteData() {
        Site site = siteRepository.findSiteByUrl(url);
        site.setStatus(Status.INDEXING);
        site.setName(getSiteName());
        site.setStatusTime(new Date());
        siteRepository.save(site);
        siteRepository.flush();
        siteRepository.delete(site);
    }

    private void saveSiteData() {
        Site site = new Site();
        site.setUrl(url);
        site.setName(getSiteName());
        site.setStatus(Status.INDEXING);
        site.setStatusTime(new Date());
        siteRepository.flush();
        siteRepository.save(site);
    }

    private List<StatisticsPage> getStatisticsPageList() throws InterruptedException {
        if (!Thread.interrupted()) {
            String urlFormat = url + "/";
            List<StatisticsPage> statisticsPageList = new Vector<>();
            List<String> urlList = new Vector<>();
            ForkJoinPool forkJoinPool = new ForkJoinPool(coreAmount);
            List<StatisticsPage> pages = forkJoinPool.invoke(new UrlParser(urlFormat, urlList, statisticsPageList, connectionConfiguration));
            return new CopyOnWriteArrayList<>(pages);
        } else throw new InterruptedException();
    }

    private void savePageList(List<StatisticsPage> statisticsPageList) throws InterruptedException {
        if (!Thread.interrupted()) {
            List<Page> pageList = new CopyOnWriteArrayList<>();
            Site site = siteRepository.findSiteByUrl(url);
            for (StatisticsPage page : statisticsPageList) {
                int first = page.getUrl().indexOf(url) + url.length();
                String format = page.getUrl().substring(first);
                pageList.add(new Page(site, format, page.getCode(),
                        page.getContent()));
            }
            pageRepository.flush();
            pageRepository.saveAll(pageList);
        } else {
            throw new InterruptedException();
        }
    }

    private void saveLemmaList() {
        if (!Thread.interrupted()) {
            Site site = siteRepository.findSiteByUrl(url);
            site.setStatusTime(new Date());
            LemmaParser.statisticsLemmaListParsing(site, pageRepository);
            List<StatisticsLemma> statisticsLemmaList = LemmaParser.getStatisticsLemmaList();
            List<Lemma> lemmaList = new CopyOnWriteArrayList<>();
            for (StatisticsLemma statisticsLemma : statisticsLemmaList) {
                lemmaList.add(new Lemma(statisticsLemma.getLemma(), statisticsLemma.getFrequency(), site));
            }
            lemmaRepository.flush();
            lemmaRepository.saveAll(lemmaList);
        } else {
            throw new RuntimeException();
        }
    }

    private void saveIndexList() throws InterruptedException {
        if (!Thread.interrupted()) {
            Site site = siteRepository.findSiteByUrl(url);
            IndexParser.statisticsIndexListParsing(site, pageRepository, lemmaRepository);
            List<StatisticsIndex> statisticsIndexList = new CopyOnWriteArrayList<>(IndexParser.getStatisticsIndexList());
            List<Index> indexList = new CopyOnWriteArrayList<>();
            site.setStatusTime(new Date());
            for (StatisticsIndex statisticsIndex : statisticsIndexList) {
                Page page = pageRepository.getReferenceById(statisticsIndex.getPageId());
                Lemma lemma = lemmaRepository.getReferenceById(statisticsIndex.getLemmaId());
                indexList.add(new Index(page, lemma, statisticsIndex.getRank()));
            }
            indexRepository.flush();
            indexRepository.saveAll(indexList);
            site.setStatusTime(new Date());
            site.setStatus(Status.INDEXED);
            siteRepository.save(site);
            log.info("Индексация завершена - " + url);
        } else {
            throw new InterruptedException();
        }
    }

    private String getSiteName() {
        List<searchengine.config.Site> siteList = sitesList.getSites();
        for (searchengine.config.Site site : siteList) {
            if (site.getUrl().equals(url)) {
                return site.getName();
            }
        }
        return "";
    }

    private void saveSiteIndexingError() {
        Site site = siteRepository.findSiteByUrl(url);
        site.setLastError("Индексация остановлена");
        site.setStatus(Status.FAILED);
        site.setStatusTime(new Date());
        siteRepository.save(site);
    }
}
