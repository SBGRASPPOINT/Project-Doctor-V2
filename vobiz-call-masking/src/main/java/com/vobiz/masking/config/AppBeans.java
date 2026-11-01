package com.vobiz.masking.config;

import com.vobiz.masking.repo.AgentDao;
import com.vobiz.masking.repo.CallDao;
import com.vobiz.masking.repo.CallbackDao;
import com.vobiz.masking.repo.SettingsDao;
import com.vobiz.masking.service.AgentService;
import com.vobiz.masking.service.CallFlowService;
import com.vobiz.masking.service.CallbackService;
import com.vobiz.masking.service.RoutingService;
import com.vobiz.masking.service.SettingsService;
import com.vobiz.masking.vobiz.VobizClient;
import com.vobiz.masking.web.Handlers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Composition root. The DataSource is auto-configured by Spring Boot (HikariCP +
 * PostgreSQL driver); everything below it is plain Java and unit-testable as such.
 */
@Configuration
@EnableConfigurationProperties({AppProperties.class, VobizProperties.class})
public class AppBeans {

    @Bean
    SettingsDao settingsDao(DataSource ds) {
        return new SettingsDao(ds);
    }

    @Bean
    AgentDao agentDao(DataSource ds) {
        return new AgentDao(ds);
    }

    @Bean
    CallDao callDao(DataSource ds) {
        return new CallDao(ds);
    }

    @Bean
    CallbackDao callbackDao(DataSource ds) {
        return new CallbackDao(ds);
    }

    @Bean
    SettingsService settingsService(SettingsDao dao) {
        return new SettingsService(dao);
    }

    @Bean
    RoutingService routingService(AgentDao agentDao, SettingsDao settingsDao) {
        return new RoutingService(agentDao, settingsDao);
    }

    @Bean
    VobizClient vobizClient(VobizProperties props) {
        return new VobizClient(props.getAuthId(), props.getAuthToken(), props.getApiBase(), props.isMock());
    }

    @Bean
    CallFlowService callFlowService(CallDao callDao, CallbackDao callbackDao, SettingsService settings,
                                    RoutingService routing, AppProperties app) {
        return new CallFlowService(callDao, callbackDao, settings, routing,
                trimSlash(app.getPublicBaseUrl()), app.getDefaultM1Number());
    }

    @Bean
    AgentService agentService(AgentDao agentDao) {
        return new AgentService(agentDao);
    }

    @Bean
    CallbackService callbackService(CallbackDao callbackDao, AgentDao agentDao, RoutingService routing,
                                    CallDao callDao, SettingsService settings, VobizClient vobiz, AppProperties app) {
        return new CallbackService(callbackDao, agentDao, routing, callDao, settings, vobiz,
                trimSlash(app.getPublicBaseUrl()), app.getDefaultM1Number());
    }

    @Bean
    Handlers handlers(AgentService agentService, CallFlowService callFlow, CallbackService callbackService,
                      SettingsService settings, CallDao callDao, CallbackDao callbackDao) {
        return new Handlers(agentService, callFlow, callbackService, settings, callDao, callbackDao);
    }

    private static String trimSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
