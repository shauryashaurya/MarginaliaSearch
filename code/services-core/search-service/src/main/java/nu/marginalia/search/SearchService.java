package nu.marginalia.search;

import com.google.gson.Gson;
import com.google.inject.Inject;
import lombok.SneakyThrows;
import nu.marginalia.WebsiteUrl;
import nu.marginalia.client.Context;
import nu.marginalia.db.storage.FileStorageService;
import nu.marginalia.db.storage.model.FileStorageType;
import nu.marginalia.linkdb.LinkdbReader;
import nu.marginalia.model.gson.GsonFactory;
import nu.marginalia.search.client.SearchMqEndpoints;
import nu.marginalia.search.svc.SearchFrontPageService;
import nu.marginalia.search.svc.*;
import nu.marginalia.service.control.ServiceEventLog;
import nu.marginalia.service.server.*;
import nu.marginalia.service.server.mq.MqNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SearchService extends Service {

    private final WebsiteUrl websiteUrl;
    private final StaticResources staticResources;
    private final FileStorageService fileStorageService;
    private final LinkdbReader linkdbReader;

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    private final ServiceEventLog eventLog;

    @SneakyThrows
    @Inject
    public SearchService(BaseServiceParams params,
                         WebsiteUrl websiteUrl,
                         StaticResources staticResources,
                         SearchFrontPageService frontPageService,
                         SearchErrorPageService errorPageService,
                         SearchAddToCrawlQueueService addToCrawlQueueService,
                         SearchFlagSiteService flagSiteService,
                         SearchQueryService searchQueryService,
                         SearchApiQueryService apiQueryService,
                         FileStorageService fileStorageService,
                         LinkdbReader linkdbReader
                             ) {
        super(params);

        this.eventLog = params.eventLog;
        this.websiteUrl = websiteUrl;
        this.staticResources = staticResources;
        this.fileStorageService = fileStorageService;
        this.linkdbReader = linkdbReader;

        Spark.staticFiles.expireTime(600);

        Spark.get("/search", searchQueryService::pathSearch);

        Gson gson = GsonFactory.get();

        Spark.get("/api/search", apiQueryService::apiSearch, gson::toJson);
        Spark.get("/public/search", searchQueryService::pathSearch);
        Spark.get("/public/site-search/:site/*", this::siteSearchRedir);
        Spark.get("/public/", frontPageService::render);
        Spark.get("/public/news.xml", frontPageService::renderNewsFeed);
        Spark.get("/public/:resource", this::serveStatic);

        Spark.post("/public/site/suggest/", addToCrawlQueueService::suggestCrawling);

        Spark.get("/public/site/flag-site/:domainId", flagSiteService::flagSiteForm);
        Spark.post("/public/site/flag-site/:domainId", flagSiteService::flagSiteAction);

        Spark.get("/site-search/:site/*", this::siteSearchRedir);


        Spark.exception(Exception.class, (e,p,q) -> {
            logger.error("Error during processing", e);
            errorPageService.serveError(Context.fromRequest(p), p, q);
        });

        Spark.awaitInitialization();
    }

    @SneakyThrows
    @MqNotification(endpoint = SearchMqEndpoints.SWITCH_LINKDB)
    public void switchLinkdb(String unusedArg) {
        logger.info("Switching link database");

        Path newPath = fileStorageService.getStorageByType(FileStorageType.LINKDB_STAGING)
                .asPath()
                .resolve("links.db");

        if (Files.exists(newPath)) {
            eventLog.logEvent("SEARCH-SWITCH-LINKDB", "");
            linkdbReader.switchInput(newPath);
        }
    }

    private Object serveStatic(Request request, Response response) {
        String resource = request.params("resource");
        staticResources.serveStatic("search", resource, request, response);
        return "";
    }

    private Object siteSearchRedir(Request request, Response response) {
        final String site = request.params("site");
        final String queryRaw = request.splat()[0];

        final String query = URLEncoder.encode(String.format("%s site:%s", queryRaw, site), StandardCharsets.UTF_8);
        final String profile = request.queryParamOrDefault("profile", "yolo");

        response.redirect(websiteUrl.withPath("search?query="+query+"&profile="+profile));

        return null;
    }



}
