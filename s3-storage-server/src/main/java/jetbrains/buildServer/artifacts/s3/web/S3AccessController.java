package jetbrains.buildServer.artifacts.s3.web;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.openapi.util.text.StringUtil;
import jetbrains.buildServer.artifacts.ExternalArtifact;
import jetbrains.buildServer.artifacts.s3.S3Constants;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.util.ServerExternalArtifactUtil;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SecurityContextEx;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import jetbrains.buildServer.serverSide.auth.SecurityContext;
import jetbrains.buildServer.storage.StorageSettingsProvider;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Created by Nikita.Skvortsov
 * date: 01.02.2017.
 */
public class S3AccessController extends BaseController {

  @NotNull
  private final StorageSettingsProvider mySettingsProvider;
  @NotNull
  private final BuildsManager myBuildsManager;
  @NotNull private final SecurityContext mySecurityContext;
  private final Cache<String, String> myLinksCache = CacheBuilder.newBuilder()
                                                                 .expireAfterWrite(40, TimeUnit.SECONDS)
                                                                 .maximumSize(100)
                                                                 .build();

  public S3AccessController(@NotNull final WebControllerManager controllerManager,
                            @NotNull final StorageSettingsProvider settingsProvider,
                            @NotNull final BuildsManager buildsManager,
                            @NotNull final SecurityContext securityContext) {
    mySettingsProvider = settingsProvider;
    myBuildsManager = buildsManager;
    mySecurityContext = securityContext;
    controllerManager.registerController(S3Constants.S3_ACCESS_CONTROLLER_PATH, this);
  }

  @Nullable
  @Override
  protected ModelAndView doHandle(@NotNull HttpServletRequest httpServletRequest, @NotNull HttpServletResponse httpServletResponse) throws Exception {
    final AuthorityHolder authorityHolder = mySecurityContext.getAuthorityHolder();

    try {
      return ((SecurityContextEx) mySecurityContext).runAs(authorityHolder, () -> {
        final Long buildId = Long.valueOf(httpServletRequest.getParameter("buildId"));
        final String path = URLDecoder.decode(httpServletRequest.getParameter("path"), "UTF-8");
        final SBuild build = myBuildsManager.findBuildInstanceById(buildId);
        if (build == null) {
          httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
          httpServletResponse.getWriter().write("Build " + buildId + " not found");
          return null;
        }

        if (StringUtil.isEmpty(path)) {
          httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
          httpServletResponse.getWriter().write("Path should not be empty");
          return null;
        }

        return ServerExternalArtifactUtil.getExternalArtifacts(build).stream()
            .filter(artifact -> artifact.getProperties().containsKey(S3Constants.S3_KEY_ATTR) && path.equals(artifact.getPath()))
            .findFirst()
            .map(artifact -> getTemporaryUrl(artifact, mySettingsProvider.getStorageSettings(build.getBuildTypeExternalId())))
            .map(url -> new ModelAndView(new RedirectView(url)))
            .orElse(null);
      });
    } catch (Throwable t) {
      throw new Exception("Failed to process request", t);
    }
  }

  private String getTemporaryUrl(@NotNull ExternalArtifact artifact,
                                 @NotNull Map<String, String> params) {
    final String key = artifact.getProperties().get(S3Constants.S3_KEY_ATTR);
    final String bucket = artifact.getProperties().get(S3Constants.S3_BUCKET_ATTR);

    try {
      return myLinksCache.get(bucket + ":" + key, () -> {
        final AmazonS3 amazonClient = S3Util.createAmazonClient(params);
        final GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, key);
        request.setExpiration(new Date(System.currentTimeMillis() + (60 * 1000)));

        URL url = amazonClient.generatePresignedUrl(request);
        return url.toString();
      });
    } catch (ExecutionException e) {
      Loggers.AGENT.infoAndDebugDetails("Failed to create presigned url for [" + key + "] in bucket [" + bucket + "]", e);
    }
    return null;
  }
}
