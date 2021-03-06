/*
 *  Copyright (C) 2020 the original author or authors.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.wehotel.plugin.auth;

import com.ctrip.framework.apollo.model.ConfigChange;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import com.wehotel.flume.clients.log4j2appender.LogService;
import com.wehotel.config.SystemConfig;
import com.wehotel.listener.AggregateRedisConfig;
import com.wehotel.util.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author lancer
 */

@Service
public class ApiConfigService {

    private static final Logger log = LoggerFactory.getLogger(ApiConfigService.class);

    private static final String fizzApiConfig        = "fizz_api_config";

    private static final String fizzApiConfigChannel = "fizz_api_config_channel";

    private static final String signHeader           = "fizz-sign";

    private static final String timestampHeader      = "fizz-ts";

    private static final String secretKeyHeader      = "fizz-secretkey";

    private Map<String, GatewayGroup> app2gatewayGroupMap = new HashMap<>(32);

    private Map<Integer, ApiConfig>   apiConfigMap        = new HashMap<>(128);

    // TODO XXX
    @Value("${serviceWhiteList:x}")
    private String serviceWhiteList;
    private Set<String> whiteListSet = new HashSet<>(196);
    @ApolloConfigChangeListener
    private void configChangeListter(ConfigChangeEvent cce) {
        cce.changedKeys().forEach(
                k -> {
                    ConfigChange cc = cce.getChange(k);
                    if (cc.getPropertyName().equalsIgnoreCase("serviceWhiteList")) {
                        log.info("old service white list: " + cc.getOldValue());
                        serviceWhiteList = cc.getNewValue();
                        afterServiceWhiteListSet();
                    }
                }
        );
    }
    public void afterServiceWhiteListSet() {
        if (StringUtils.isNotBlank(serviceWhiteList)) {
            whiteListSet.clear();
            Arrays.stream(StringUtils.split(serviceWhiteList, Constants.Symbol.COMMA)).forEach(s -> {
                whiteListSet.add(s);
            });
            log.info("new service white list: " + whiteListSet.toString());
        } else {
            log.info("no service white list");
        }
    }

    @Resource(name = AggregateRedisConfig.AGGREGATE_REACTIVE_REDIS_TEMPLATE)
    private ReactiveStringRedisTemplate rt;

    @Resource
    private SystemConfig systemConfig;

    @Resource
    private AppService appService;

    @Autowired(required = false)
    private CustomAuth customAuth;

    @PostConstruct
    public void init() throws Throwable {

        afterServiceWhiteListSet(); // TODO XXX

        final Throwable[] throwable = new Throwable[1];
        Throwable error = Mono.just(Objects.requireNonNull(rt.opsForHash().entries(fizzApiConfig)
                .defaultIfEmpty(new AbstractMap.SimpleEntry<>(ReactorUtils.OBJ, ReactorUtils.OBJ)).onErrorStop().doOnError(t -> {
                    log.info(null, t);
                })
                .concatMap(e -> {
                    Object k = e.getKey();
                    if (k == ReactorUtils.OBJ) {
                        return Flux.just(e);
                    }
                    Object v = e.getValue();
                    log.info(k.toString() + Constants.Symbol.COLON + v.toString(), LogService.BIZ_ID, k.toString());
                    String json = (String) v;
                    try {
                        ApiConfig ac = JacksonUtils.readValue(json, ApiConfig.class);
                        apiConfigMap.put(ac.id, ac);
                        updateApp2gatewayGroupMap(ac);
                        return Flux.just(e);
                    } catch (Throwable t) {
                        throwable[0] = t;
                        log.info(json, t);
                        return Flux.error(t);
                    }
                }).blockLast())).flatMap(
                e -> {
                    if (throwable[0] != null) {
                        return Mono.error(throwable[0]);
                    }
                    return lsnApiConfigChange();
                }
        ).block();
        if (error != ReactorUtils.EMPTY_THROWABLE) {
            throw error;
        }
    }

    public Mono<Throwable> lsnApiConfigChange() {
        final Throwable[] throwable = new Throwable[1];
        final boolean[] b = {false};
        rt.listenToChannel(fizzApiConfigChannel).doOnError(t -> {
            throwable[0] = t;
            b[0] = false;
            log.error("lsn " + fizzApiConfigChannel, t);
        }).doOnSubscribe(
                s -> {
                    b[0] = true;
                    log.info("success to lsn on " + fizzApiConfigChannel);
                }
        ).doOnNext(msg -> {
            String json = msg.getMessage();
            log.info(json, LogService.BIZ_ID, "acc" + System.currentTimeMillis());
            try {
                ApiConfig ac = JacksonUtils.readValue(json, ApiConfig.class);
                ApiConfig r = apiConfigMap.remove(ac.id);
                if (ac.isDeleted != ApiConfig.DELETED && r != null) {
                    r.isDeleted = ApiConfig.DELETED;
                    updateApp2gatewayGroupMap(r);
                }
                updateApp2gatewayGroupMap(ac);
                if (ac.isDeleted != ApiConfig.DELETED) {
                    apiConfigMap.put(ac.id, ac);
                }
            } catch (Throwable t) {
                log.info(json, t);
            }
        }).subscribe();
        Throwable t = throwable[0];
        while (!b[0]) {
            if (t != null) {
                return Mono.error(t);
            } else {
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException e) {
                    return Mono.error(e);
                }
            }
        }
        return Mono.just(ReactorUtils.EMPTY_THROWABLE);
    }

    private void updateApp2gatewayGroupMap(ApiConfig ac) {
        GatewayGroup gg = app2gatewayGroupMap.get(ac.app());
        if (ac.isDeleted == ApiConfig.DELETED) {
            if (gg == null) {
                log.info("no gateway group for " + ac.app());
            } else {
                gg.remove(ac);
                if (gg.getServiceConfigMap().isEmpty()) {
                    app2gatewayGroupMap.remove(ac.app());
                }
            }
        } else {
            if (gg == null) {
                gg = new GatewayGroup(ac.gatewayGroup);
                app2gatewayGroupMap.put(ac.app(), gg);
                gg.add(ac);
            } else {
                gg.update(ac);
            }
        }
    }

    public enum Access {

        YES                               (null),

        CANT_ACCESS_CURRENT_GATEWAY_GROUP ("cant access current gateway group"),

        NO_GATEWAY_GROUP_FOR_APP          ("no gateway group for app"),

        NO_APP_CONFIG_FOR_APP             ("no app config for app"),

        ORIGIN_IP_NOT_IN_WHITE_LIST       ("origin ip not in white list"),

        NO_TIMESTAMP_OR_SIGN              ("no timestamp or sign"),

        SIGN_INVALID                      ("sign invalid"),

        NO_CUSTOM_AUTH                    ("no custom auth"),

        CUSTOM_AUTH_REJECT                ("custom auth reject"),

        SERVICE_NOT_OPEN                  ("service not open"),

        NO_SERVICE_EXPOSE_TO_APP          ("no service expose to app"),

        SERVICE_API_NOT_EXPOSE_TO_APP     ("service api not expose to app"),

        CANT_ACCESS_SERVICE_API           ("cant access service api");

        private String reason;

        Access(String r) {
            reason = r;
        }

        public String getReason() {
            return reason;
        }
    }

    public Mono<Object> canAccess(ServerWebExchange exchange) {

        ServerHttpRequest req = exchange.getRequest();
        HttpHeaders hdrs = req.getHeaders();
        LogService.setBizId(req.getId());
        return canAccess(exchange, WebUtils.getAppId(exchange),     WebUtils.getOriginIp(exchange), hdrs.getFirst(timestampHeader), hdrs.getFirst(signHeader), hdrs.getFirst(secretKeyHeader),
                                   WebUtils.getServiceId(exchange), req.getMethod(),                WebUtils.getReqPath(exchange));
    }

    private Mono<Object> canAccess(ServerWebExchange exchange, String app,     String     ip,     String timestamp, String sign, String secretKey,
                                                               String service, HttpMethod method, String path) {

        GatewayGroup gg = app2gatewayGroupMap.get(app); boolean toCorBapp = App.TO_C.equals(app) || App.TO_B.equals(app);

        if (gg == null) {
                if (toCorBapp) { return Mono.just(Access.YES); } else { return logWarnAndResult("no gateway group for " + app, Access.NO_GATEWAY_GROUP_FOR_APP); }
        } else {
                Set<Character> currentServerGatewayGroupSet = systemConfig.getCurrentServerGatewayGroupSet();
                if (currentServerGatewayGroupSet.contains(gg.id)) {
                        Mono<Access> am = Mono.just(Access.YES);
                        App a = appService.getApp(app);
                        if (a == null) {
                                if (!toCorBapp) { return logWarnAndResult("no app config for " + app, Access.NO_APP_CONFIG_FOR_APP); }
                        } else if (a.useWhiteList && !a.ips.contains(ip)) {
                                return logWarnAndResult(ip + " not in " + app + " white list", Access.ORIGIN_IP_NOT_IN_WHITE_LIST);
                        } else if (a.useAuth) {
                                if (a.authType == App.SIGN_AUTH) {
                                    if (StringUtils.isBlank(timestamp) || StringUtils.isBlank(sign)) { return logWarnAndResult(app + " lack timestamp " + timestamp + " or sign " + sign, Access.NO_TIMESTAMP_OR_SIGN); }
                                    else if (!validate(app, timestamp, a.secretkey, sign))           { return logWarnAndResult(app + " sign " + sign + " invalid", Access.SIGN_INVALID); }
                                } else if (customAuth == null) {
                                    return logWarnAndResult(app + " no custom auth", Access.NO_CUSTOM_AUTH);
                                } else {
                                    am = customAuth.auth(exchange, app, ip, timestamp, sign, secretKey, a);
                                }
                        }
                        return am.flatMap(
                                v -> {
                                    LogService.setBizId(exchange.getRequest().getId());
                                    if (v == Access.CUSTOM_AUTH_REJECT || v != Access.YES) { return Mono.just(Access.CUSTOM_AUTH_REJECT); }
                                    if (!whiteListSet.contains(service))                   { return Mono.just(Access.SERVICE_NOT_OPEN); } // TODO XXX
                                    ServiceConfig sc = gg.getServiceConfig(service);
                                    if (sc == null) {
                                        if (toCorBapp) { return Mono.just(Access.YES); } else { return logWarnAndResult("no service expose to " + app, Access.NO_SERVICE_EXPOSE_TO_APP); }
                                    } else {
                                        ApiConfig ac = sc.getApiConfig(method, path);
                                        if (ac == null) {
                                            if (toCorBapp) { return Mono.just(Access.YES); } else { return logWarnAndResult(service + ' ' + method.name() + ' ' + path + " not expose to " + app, Access.SERVICE_API_NOT_EXPOSE_TO_APP); }
                                        } else if (ac.access == ApiConfig.ALLOW) {
                                            return Mono.just(ac);
                                        } else {
                                            return logWarnAndResult(app + " cant access " + service + ' ' + method.name() + ' ' + path, Access.CANT_ACCESS_SERVICE_API);
                                        }
                                    }
                                }
                        );

                } else {
                        return logWarnAndResult(app + " cant access " + currentServerGatewayGroupSet, Access.CANT_ACCESS_CURRENT_GATEWAY_GROUP);
                }
        }
    }

    private static Mono logWarnAndResult(String msg, Access access) {
        log.warn(msg);
        return Mono.just(access);
    }

    private static boolean validate(String app, String timestamp, String secretKey, String sign) {
        StringBuilder b = new StringBuilder(128);
        b.append(app).append(Constants.Symbol.UNDERLINE).append(timestamp).append(Constants.Symbol.UNDERLINE).append(secretKey);
        return sign.equals(DigestUtils.md532(b.toString()));
    }

    public Map<String, GatewayGroup> getApp2gatewayGroupMap() {
        return app2gatewayGroupMap;
    }
}
