/*
 * Copyright (c) 2008. All rights reserved.
 */
package ro.isdc.wro.model.group.processor;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collection;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.cache.CacheKey;
import ro.isdc.wro.config.ReadOnlyContext;
import ro.isdc.wro.manager.callback.LifecycleCallbackRegistry;
import ro.isdc.wro.model.WroModel;
import ro.isdc.wro.model.factory.WroModelFactory;
import ro.isdc.wro.model.group.Group;
import ro.isdc.wro.model.group.Inject;
import ro.isdc.wro.model.resource.Resource;
import ro.isdc.wro.model.resource.processor.ResourcePostProcessor;
import ro.isdc.wro.model.resource.processor.ResourcePreProcessor;
import ro.isdc.wro.model.resource.processor.decorator.DefaultProcessorDecorator;
import ro.isdc.wro.model.resource.processor.decorator.ProcessorDecorator;
import ro.isdc.wro.model.resource.processor.factory.ProcessorsFactory;
import ro.isdc.wro.util.StopWatch;


/**
 * Default group processor which perform preProcessing, merge and postProcessing on groups resources.
 *
 * @author Alex Objelean
 * @created Created on Nov 3, 2008
 */
public class GroupsProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(GroupsProcessor.class);
  @Inject
  private LifecycleCallbackRegistry callbackRegistry;
  @Inject
  private ProcessorsFactory processorsFactory;
  @Inject
  private WroModelFactory modelFactory;
  @Inject
  private ReadOnlyContext context;
  @Inject
  private Injector injector;

  /**
   * This field is transient because {@link PreProcessorExecutor} is not serializable (according to findbugs eclipse
   * plugin).
   */
  @Inject
  private transient PreProcessorExecutor preProcessorExecutor;

  /**
   * @param cacheKey
   *          to process.
   * @return processed content.
   */
  public String process(final CacheKey cacheKey) {
    Validate.notNull(cacheKey);
    try {
      LOG.debug("Starting processing group [{}] of type [{}] with minimized flag: " + cacheKey.isMinimize(),
          cacheKey.getGroupName(), cacheKey.getType());
      // find processed result for a group
      final WroModel model = modelFactory.create();
      final Group group = model.getGroupByName(cacheKey.getGroupName());
      final Group filteredGroup = group.collectResourcesOfType(cacheKey.getType());
      if (filteredGroup.getResources().isEmpty()) {
        LOG.debug("No resources found in group: {} and resource type: {}", group.getName(), cacheKey.getType());
        if (!context.getConfig().isIgnoreEmptyGroup()) {
          throw new WroRuntimeException("No resources found in group: " + group.getName());
        }
      }
      final String result = preProcessorExecutor.processAndMerge(filteredGroup.getResources(), cacheKey.isMinimize());
      return applyPostProcessors(cacheKey, result);
    } catch (final IOException e) {
      throw new WroRuntimeException("Exception while merging resources: " + e.getMessage(), e).logError();
    } finally {
      callbackRegistry.onProcessingComplete();
    }
  }

  /**
   * Apply resourcePostProcessors.
   *
   * @param cacheKey
   *          the {@link CacheKey} being processed.
   * @param content
   *          to process with all postProcessors.
   * @return the post processed content.
   */
  private String applyPostProcessors(final CacheKey cacheKey, final String content)
      throws IOException {
    final Collection<ResourcePostProcessor> processors = processorsFactory.getPostProcessors();
    if (processors.isEmpty()) {
      return content;
    }
    final Resource resource = Resource.create(cacheKey.getGroupName(), cacheKey.getType());

    Reader reader = new StringReader(content.toString());
    Writer writer = null;
    final StopWatch stopWatch = new StopWatch();
    for (final ResourcePostProcessor processor : processors) {
      final ResourcePreProcessor decoratedProcessor = decorateProcessor(processor, cacheKey.isMinimize());
      stopWatch.start("Using " + decoratedProcessor.toString());
      writer = new StringWriter();
      try {
        callbackRegistry.onBeforePostProcess();
        //the processor is invoked as a pre processor. This is important for correct computation of eligibility.
        decoratedProcessor.process(resource, reader, writer);
      } finally {
        stopWatch.stop();
        callbackRegistry.onAfterPostProcess();
        IOUtils.closeQuietly(reader);
        IOUtils.closeQuietly(writer);
      }
      reader = new StringReader(writer.toString());
    }
    LOG.debug(stopWatch.prettyPrint());
    return writer.toString();
  }

  /**
   * @return a decorated processor.
   */
  private ProcessorDecorator decorateProcessor(final ResourcePostProcessor processor, final boolean minimize) {
    final ProcessorDecorator decorated = new DefaultProcessorDecorator(processor, minimize);
    injector.inject(decorated);
    return decorated;
  }
}
