package io.apiman.plugins.cookie_remove_policy;

import io.apiman.gateway.engine.async.IAsyncResult;
import io.apiman.gateway.engine.async.IAsyncResultHandler;
import io.apiman.gateway.engine.beans.PolicyFailure;
import io.apiman.gateway.engine.beans.PolicyFailureType;
import io.apiman.gateway.engine.beans.ApiRequest;
import io.apiman.gateway.engine.beans.ApiResponse;
import io.apiman.gateway.engine.beans.exceptions.ConfigurationParseException;
import io.apiman.gateway.engine.io.AbstractStream;
import io.apiman.gateway.engine.io.IApimanBuffer;
import io.apiman.gateway.engine.io.IReadWriteStream;
import io.apiman.gateway.engine.policies.AbstractMappedDataPolicy;
import io.apiman.gateway.engine.policies.AbstractMappedPolicy;
import io.apiman.gateway.engine.policy.IPolicyChain;
import io.apiman.gateway.engine.policy.IPolicyContext;
import io.apiman.plugins.cookie_remove_policy.beans.CookieRemoveConfigBean;
import io.apiman.plugins.session.beans.ResponseBehaviour;
import io.apiman.plugins.session.exception.InvalidConfigurationException;
import io.apiman.plugins.session.model.Cookie;
import io.apiman.plugins.session.store.ISessionStore;
import io.apiman.plugins.session.store.SessionStoreFactory;
import io.apiman.plugins.session.util.ConfigValidator;
import io.apiman.plugins.session.util.Constants;
import io.apiman.plugins.session.util.CookieUtil;
import io.apiman.plugins.session.util.Messages;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.regex.Pattern;

/**
 * Policy that removes a cookie.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public class CookieRemovePolicy extends AbstractMappedDataPolicy<CookieRemoveConfigBean> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CookieRemovePolicy.class);
    private static final String ATTRIBUTE_REMOVE_COOKIE = CookieRemovePolicy.class.getCanonicalName() + ".removeCookie";
    private static final String ATTRIBUTE_SKIP = CookieRemovePolicy.class.getCanonicalName() + ".skipPolicy";
    private static final Messages MESSAGES = new Messages("io.apiman.plugins.cookie_remove_policy.messages");
    private Pattern pathMatcher;

    /**
     * See {@link AbstractMappedPolicy#getConfigurationClass()}
     */
    @Override
    protected Class<CookieRemoveConfigBean> getConfigurationClass() {
        return CookieRemoveConfigBean.class;
    }

    /**
     * See {@link AbstractMappedPolicy#parseConfiguration(String)}
     */
    @Override
    public CookieRemoveConfigBean parseConfiguration(String jsonConfiguration) throws ConfigurationParseException {
        final CookieRemoveConfigBean config = super.parseConfiguration(jsonConfiguration);

        // validate configuration
        final ConfigValidator validator = ConfigValidator.build()
                .validate(config.getInvalidateSession(), "Invalidate session setting")
                .validate(config.getCookieName(), "Cookie name")
                .validate(config.getSkipBackendCall(), "Skip backend call setting")
                .validate(config.getResponseBehaviour(), "Response behaviour")
                .validate(new ConfigValidator.Validator() {
                    @Override
                    public boolean isValid() {
                        // redirect URL should be set
                        return (ResponseBehaviour.PassThrough.equals(config.getResponseBehaviour()) ||
                                StringUtils.isNotBlank(config.getRedirectUrl()));
                    }
                }, "Redirect URL");

        if (!validator.isValid()) {
            throw new InvalidConfigurationException(MESSAGES.formatEach(
                    "CookieRemovePolicy.ConfigNotSet", validator.getValidationErrors()));
        }

        // configure path matcher
        if (StringUtils.isNotBlank(config.getPathMatcher())) {
            pathMatcher = Pattern.compile(config.getPathMatcher());
        } else {
            LOGGER.warn(MESSAGES.format("CookieRemovePolicy.PathMatcherNotSet"));
        }

        return config;
    }

    /**
     * See {@link AbstractMappedPolicy#doApply(ApiResponse, IPolicyContext, Object, IPolicyChain)}
     */
    @Override
    protected void doApply(ApiRequest request, IPolicyContext context, final CookieRemoveConfigBean config,
                           IPolicyChain<ApiRequest> chain) {

        // skip if path matcher is not use, or request URL doesn't match
        if (null == pathMatcher || !pathMatcher.matcher(request.getDestination()).matches()) {
            LOGGER.debug(MESSAGES.format("CookieRemovePolicy.PathMatchFalse"));

            context.setAttribute(ATTRIBUTE_SKIP, true);
            chain.doApply(request);
            return;
        }

        final Cookie cookie = CookieUtil.getCookie(request, config.getCookieName());
        if (null == cookie) {
            // cookie is absent - continue
            LOGGER.warn(MESSAGES.format("CookieRemovePolicy.CookieAbsent", config.getCookieName()));
            success(request, context, config, chain);

        } else {
            final String sessionId = cookie.getValue();

            if (StringUtils.isEmpty(sessionId)) {
                // cookie is empty - continue
                LOGGER.warn(MESSAGES.format("CookieRemovePolicy.CookieEmpty", config.getCookieName()));
                success(request, context, config, chain);

            } else {
                // cookie is present - mark for removal
                context.setAttribute(ATTRIBUTE_REMOVE_COOKIE, cookie);

                if (config.getInvalidateSession()) {
                    LOGGER.debug(MESSAGES.format("CookieRemovePolicy.AttemptingInvalidation", sessionId));
                    invalidateSession(sessionId, request, context, chain, config);

                } else {
                    LOGGER.debug(MESSAGES.format("CookieRemovePolicy.InvalidationDisabled", sessionId));

                    // continue
                    success(request, context, config, chain);
                }
            }
        }
    }

    /**
     * See {@link AbstractMappedPolicy#doApply(ApiResponse, IPolicyContext, Object, IPolicyChain)}
     */
    @Override
    protected void doApply(ApiResponse response, IPolicyContext context, CookieRemoveConfigBean config,
                           IPolicyChain<ApiResponse> chain) {

        // short-circuit
        if (context.getAttribute(ATTRIBUTE_SKIP, false)) {
            chain.doApply(response);
            return;
        }

        final Cookie cookie = context.getAttribute(ATTRIBUTE_REMOVE_COOKIE, null);
        if (null != cookie) {
            removeCookie(response, config, cookie);
        } else {
            LOGGER.debug(MESSAGES.format("CookieRemovePolicy.RemovalSkipped"));
        }

        if (ResponseBehaviour.Redirect.equals(config.getResponseBehaviour())) {
            LOGGER.info(MESSAGES.format("CookieRemovePolicy.Redirecting", config.getRedirectUrl()));

            // remove existing Content-Length header before continuing chain, as API response will not be returned
            response.getHeaders().remove(Constants.HEADER_CONTENT_LENGTH);

            // redirect client
            response.setCode(HttpURLConnection.HTTP_MOVED_TEMP);
            response.getHeaders().put(Constants.HEADER_LOCATION, config.getRedirectUrl());
        }

        chain.doApply(response);
    }

    /**
     * See {@link AbstractMappedDataPolicy#requestDataHandler(ApiRequest, IPolicyContext, Object)}
     */
    @Override
    protected IReadWriteStream<ApiRequest> requestDataHandler(ApiRequest request, IPolicyContext context,
                                                                  CookieRemoveConfigBean config) {
        return null;
    }

    /**
     * See {@link AbstractMappedDataPolicy#responseDataHandler(ApiResponse, IPolicyContext, Object)}
     */
    @Override
    protected IReadWriteStream<ApiResponse> responseDataHandler(final ApiResponse response,
                                                                    final IPolicyContext context,
                                                                    final CookieRemoveConfigBean config) {
        // short-circuit
        if (context.getAttribute(ATTRIBUTE_SKIP, false)) {
            return null;
        }

        if (ResponseBehaviour.PassThrough.equals(config.getResponseBehaviour())) {
            // default response behaviour
            return null;

        } else {
            // discard response body from back-end service
            return new AbstractStream<ApiResponse>() {
                @Override
                protected void handleHead(ApiResponse head) {
                }

                @Override
                public ApiResponse getHead() {
                    return response;
                }

                @Override
                public void write(IApimanBuffer chunk) {
                    // discard chunk
                }
            };
        }
    }

    /**
     * Either continue the policy chain or skip, depending on the policy configuration.
     *
     * @param request the service request
     * @param context the policy context
     * @param config  the policy configuration
     * @param chain   the policy chain
     */
    private void success(ApiRequest request, IPolicyContext context, CookieRemoveConfigBean config,
                         IPolicyChain<ApiRequest> chain) {

        if (config.getSkipBackendCall()) {
            // don't call the back-end service
            LOGGER.info(MESSAGES.format("CookieRemovePolicy.SkippingBackEndCall"));

            context.setConnectorInterceptor(new ShortcircuitConnectorInterceptor());
            chain.doSkip(request);

        } else {
            // call the back-end service
            chain.doApply(request);
        }
    }

    /**
     * Instruct the browser to remove the cookie in the response.
     *
     * @param response the service response
     * @param config   the policy configuration
     * @param cookie   the cookie to remove
     */
    private void removeCookie(ApiResponse response, CookieRemoveConfigBean config, Cookie cookie) {
        // align cookie properties to ensure deletion
        cookie.setPath(config.getCookiePath());

        // invalidate cookie in the response
        LOGGER.debug(MESSAGES.format("CookieRemovePolicy.AttemptingRemoval"));
        CookieUtil.removeCookie(response, cookie);
    }

    /**
     * Invalidate session data for the given session, then manipulate the chain accordingly.
     *
     * @param sessionId the ID of the session
     * @param request   the service request
     * @param context   the policy context
     * @param chain     the policy chain
     * @param config    the policy configuration
     */
    private void invalidateSession(final String sessionId, final ApiRequest request, final IPolicyContext context,
                                   final IPolicyChain<ApiRequest> chain, final CookieRemoveConfigBean config) {

        final ISessionStore sessionStore = SessionStoreFactory.getSessionStore(context);
        sessionStore.deleteSession(sessionId, new IAsyncResultHandler<Void>() {
            @Override
            public void handle(IAsyncResult<Void> result) {
                if (result.isSuccess()) {
                    // session data removed
                    LOGGER.info(MESSAGES.format("CookieRemovePolicy.SessionInvalidated", sessionId));
                    success(request, context, config, chain);

                } else {
                    // failed to remove session data
                    final String failureMessage = MESSAGES.format(
                            "CookieRemovePolicy.SessionInvalidationFailed", sessionId);
                    LOGGER.error(failureMessage, result.getError());

                    // policy failure
                    chain.doFailure(new PolicyFailure(PolicyFailureType.Other,
                            HttpURLConnection.HTTP_INTERNAL_ERROR, failureMessage));
                }
            }
        });
    }
}