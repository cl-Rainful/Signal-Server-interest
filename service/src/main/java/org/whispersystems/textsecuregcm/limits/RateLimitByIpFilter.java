/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.limits;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import javax.inject.Provider;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.controllers.RateLimitExceededException;
import org.whispersystems.textsecuregcm.mappers.RateLimitExceededExceptionMapper;
import org.whispersystems.textsecuregcm.util.HeaderUtils;
import org.whispersystems.textsecuregcm.util.HttpServletRequestUtil;

public class RateLimitByIpFilter implements ContainerRequestFilter {

  private static final Logger logger = LoggerFactory.getLogger(RateLimitByIpFilter.class);

  @Context
  private Provider<HttpServletRequest> httpServletRequestProvider;

  @VisibleForTesting
  static final RateLimitExceededException INVALID_HEADER_EXCEPTION = new RateLimitExceededException(Duration.ofHours(1),
      true);

  private static final ExceptionMapper<RateLimitExceededException> EXCEPTION_MAPPER = new RateLimitExceededExceptionMapper();

  private final RateLimiters rateLimiters;
  private final boolean useRemoteAddress;


  public RateLimitByIpFilter(final RateLimiters rateLimiters, final boolean useRemoteAddress) {
    this.rateLimiters = requireNonNull(rateLimiters);
    this.useRemoteAddress = useRemoteAddress;
  }

  @Override
  public void filter(final ContainerRequestContext requestContext) throws IOException {
    // requestContext.getUriInfo() should always be an instance of `ExtendedUriInfo`
    // in the Jersey client
    if (!(requestContext.getUriInfo() instanceof final ExtendedUriInfo uriInfo)) {
      return;
    }

    final RateLimitedByIp annotation = uriInfo.getMatchedResourceMethod()
        .getInvocable()
        .getHandlingMethod()
        .getAnnotation(RateLimitedByIp.class);

    if (annotation == null) {
      return;
    }

    final RateLimiters.For handle = annotation.value();

    try {
      final String xffHeader = requestContext.getHeaders().getFirst(HttpHeaders.X_FORWARDED_FOR);
      final Optional<String> remoteAddress = useRemoteAddress
          ? Optional.of(HttpServletRequestUtil.getRemoteAddress(httpServletRequestProvider.get()))
          : Optional.ofNullable(xffHeader)
              .flatMap(HeaderUtils::getMostRecentProxy);

      // checking if we failed to extract the most recent IP from the X-Forwarded-For header
      // for any reason
      if (remoteAddress.isEmpty()) {
        // checking if annotation is configured to fail when the most recent IP is not resolved
        if (annotation.failOnUnresolvedIp()) {
          logger.error("Missing/bad X-Forwarded-For: {}", xffHeader);
          throw INVALID_HEADER_EXCEPTION;
        }
        // otherwise, allow request
        return;
      }

      final RateLimiter rateLimiter = rateLimiters.forDescriptor(handle);
      rateLimiter.validate(remoteAddress.get());
    } catch (RateLimitExceededException e) {
      final Response response = EXCEPTION_MAPPER.toResponse(e);
      throw new ClientErrorException(response);
    }
  }
}
