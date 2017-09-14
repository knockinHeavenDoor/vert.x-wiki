package wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wiki.database.WikiDatabaseService;

import java.util.Date;

public class HttpServerVerticle extends AbstractVerticle {

    public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
    public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

    private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

    private static final String EMPTY_PAGE_MARKDOWN =
            "# A new page\n" +
                    "\n" +
                    "Feel-free to write in Markdown!\n";

    // tag::db-consume[]
    private WikiDatabaseService dbService;

    private WebClient webClient;

    @Override
    public void start(Future<Void> startFuture) throws Exception {

        String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue");
        dbService = WikiDatabaseService.createProxy(vertx, wikiDbQueue);

        webClient = WebClient.create(vertx, new WebClientOptions()
                .setSsl(true)
                .setUserAgent("vert-x3"));

        HttpServer server = vertx.createHttpServer();
        // (...)
        // end::db-consume[]

        Router router = Router.router(vertx);
        router.get("/").handler(this::indexHandler);
        router.get("/wiki/:page").handler(this::pageRenderingHandler);
        router.post().handler(BodyHandler.create());
        router.post("/save").handler(this::pageUpdateHandler);
        router.post("/create").handler(this::pageCreateHandler);
        router.post("/delete").handler(this::pageDeletionHandler);
        router.get("/backup").handler(this::backupHandler);

        int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
        server
                .requestHandler(router::accept)
                .listen(portNumber, ar -> {
                    if (ar.succeeded()) {
                        LOGGER.info("HTTP server running on port " + portNumber);
                        startFuture.complete();
                    } else {
                        LOGGER.error("Could not start a HTTP server", ar.cause());
                        startFuture.fail(ar.cause());
                    }
                });
    }


    private void indexHandler(RoutingContext context) {
        dbService.fetchAllPages(reply -> {
            if (reply.succeeded()) {
                context.put("title", "Wiki home");
                context.put("pages", reply.result().getList());
                templateEngine.render(context, "templates", "/index.ftl", ar -> {
                    if (ar.succeeded()) {
                        context.response().putHeader("Content-Type", "text/html");
                        context.response().end(ar.result());
                    } else {
                        context.fail(ar.cause());
                    }
                });
            } else {
                context.fail(reply.cause());
            }
        });
    }

    private void pageRenderingHandler(RoutingContext context) {
        String requestedPage = context.request().getParam("page");
        dbService.fetchPage(requestedPage, reply -> {
            if (reply.succeeded()) {

                JsonObject payLoad = reply.result();
                boolean found = payLoad.getBoolean("found");
                String rawContent = payLoad.getString("rawContent", EMPTY_PAGE_MARKDOWN);
                context.put("title", requestedPage);
                context.put("id", payLoad.getInteger("id", -1));
                context.put("newPage", found ? "no" : "yes");
                context.put("rawContent", rawContent);
                context.put("content", Processor.process(rawContent));
                context.put("timestamp", new Date().toString());

                templateEngine.render(context, "templates", "/page.ftl", ar -> {
                    if (ar.succeeded()) {
                        context.response().putHeader("Content-Type", "text/html");
                        context.response().end(ar.result());
                    } else {
                        context.fail(ar.cause());
                    }
                });

            } else {
                context.fail(reply.cause());
            }
        });
    }

    private void pageUpdateHandler(RoutingContext context) {
        String title = context.request().getParam("title");

        Handler<AsyncResult<Void>> handler = reply -> {
            if (reply.succeeded()) {
                context.response().setStatusCode(303);
                context.response().putHeader("Location", "/wiki/" + title);
                context.response().end();
            } else {
                context.fail(reply.cause());
            }
        };

        String markdown = context.request().getParam("markdown");
        if ("yes".equals(context.request().getParam("newPage"))) {
            dbService.createPage(title, markdown, handler);
        } else {
            dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler);
        }
    }

    private void pageCreateHandler(RoutingContext context) {
        String pageName = context.request().getParam("name");
        String location = "/wiki/" + pageName;
        if (pageName == null || pageName.isEmpty()) {
            location = "/";
        }
        context.response().setStatusCode(303);
        context.response().putHeader("Location", location);
        context.response().end();
    }

    private void pageDeletionHandler(RoutingContext context) {
        dbService.deletePage(Integer.valueOf(context.request().getParam("id")), reply -> {
            if (reply.succeeded()) {
                context.response().setStatusCode(303);
                context.response().putHeader("Location", "/");
                context.response().end();
            } else {
                context.fail(reply.cause());
            }
        });
    }

    private void backupHandler(RoutingContext context) {
        dbService.fetchAllPagesData(reply -> {
            if (reply.succeeded()) {

                JsonObject filesObject = new JsonObject();
                JsonObject gistPayload = new JsonObject() //(1)Gist创建请求有效负载是GitHub API文档中概述的JSON 文档
                        .put("files", filesObject)
                        .put("description", "A wiki backup")
                        .put("public", true);

                reply
                        .result()
                        .forEach(page -> {
                            JsonObject fileObject = new JsonObject(); //(2)每个文件是files有效负载对象下的条目，其中标题是关键字，值是文本
                            filesObject.put(page.getString("NAME"), fileObject);
                            fileObject.put("content", page.getString("CONTENT"));
                        });

                webClient.post(443, "api.github.com", "/gists") //(3)Web客户端需要POST在端口443（HTTPS）上发出请求，路径必须为/gists
                        .putHeader("Accept", "application/vnd.github.v3+json") //(4)Accept在application/vnd.github.v3+jsonMIME类型的请求中必须有头，否则请求失败。在下一行指定有效负载是JSON对象也很重要
                        .putHeader("Content-Type", "application/json")
                        .as(BodyCodec.jsonObject()) //(5)BodyCodec类提供了一个辅助指定该响应将被直接转换为Vert.x JsonObject实例。也可以使用BodyCodec#json(Class<T>)JSON数据并将其映射到类型为Java的对象T（这将使用Jackson数据映射）
                        .sendJsonObject(gistPayload, ar -> {  //(6)sendJsonObject 是使用JSON有效负载触发HTTP请求的帮手
                            if (ar.succeeded()) {
                                HttpResponse<JsonObject> response = ar.result();
                                if (response.statusCode() == 201) {
                                    context.put("backup_gist_url", response.body().getString("html_url"));  //(7)成功后，我们可以遍历JSON数据（html_url密钥）以获取新创建的Gist的用户友好的URL
                                    indexHandler(context);
                                } else {
                                    StringBuilder message = new StringBuilder()
                                            .append("Could not backup the wiki: ")
                                            .append(response.statusMessage());
                                    JsonObject body = response.body();
                                    if (body != null) {
                                        message.append(System.getProperty("line.separator"))
                                                .append(body.encodePrettily());
                                    }
                                    LOGGER.error(message.toString());
                                    context.fail(502);
                                }
                            } else {
                                Throwable err = ar.cause();
                                LOGGER.error("HTTP Client error", err);
                                context.fail(err);
                            }
                        });

            } else {
                context.fail(reply.cause());
            }
        });
    }
}