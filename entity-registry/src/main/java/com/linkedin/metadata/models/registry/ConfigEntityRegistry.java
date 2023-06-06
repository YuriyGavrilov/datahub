package com.linkedin.metadata.models.registry;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.DataSchemaFactory;
import com.linkedin.metadata.models.DefaultEntitySpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.EntitySpecBuilder;
import com.linkedin.metadata.models.EventSpec;
import com.linkedin.metadata.models.registry.config.Entities;
import com.linkedin.metadata.models.registry.config.Entity;
import com.linkedin.metadata.models.registry.config.Event;
import com.linkedin.metadata.models.registry.template.AspectTemplateEngine;
import com.linkedin.util.Pair;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

import static com.linkedin.metadata.Constants.*;
import static com.linkedin.metadata.models.registry.EntityRegistryUtils.*;


/**
 * Implementation of {@link EntityRegistry} that builds {@link DefaultEntitySpec} objects
 * from an entity registry config yaml file
 */
@Slf4j
public class ConfigEntityRegistry implements EntityRegistry {

  private final DataSchemaFactory dataSchemaFactory;
  private final Map<String, EntitySpec> entityNameToSpec;
  private final Map<String, EventSpec> eventNameToSpec;
  private final List<EntitySpec> entitySpecs;
  private final String identifier;
  private final Map<String, AspectSpec> _aspectNameToSpec;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());
  static {
    int maxSize = Integer.parseInt(System.getenv().getOrDefault(INGESTION_MAX_SERIALIZED_STRING_LENGTH, MAX_JACKSON_STRING_SIZE));
    OBJECT_MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(maxSize).build());
  }

  public ConfigEntityRegistry(Pair<Path, Path> configFileClassPathPair) throws IOException {
    //TODO Not sure in what case we would need the custom classpath for the config entity registry
    // when Plugin mechanism exists removing but should be discussed before merging
    this(DataSchemaFactory.getInstance(), configFileClassPathPair.getFirst());
  }

  public ConfigEntityRegistry(String entityRegistryRoot) throws EntityRegistryException, IOException {
    this(EntityRegistryUtils.getFileAndClassPath(entityRegistryRoot));
  }

  public ConfigEntityRegistry(InputStream configFileInputStream) {
    this(DataSchemaFactory.getInstance(), configFileInputStream);
  }

  public ConfigEntityRegistry(DataSchemaFactory dataSchemaFactory, Path configFilePath) throws FileNotFoundException {
    this(dataSchemaFactory, new FileInputStream(configFilePath.toString()));
  }

  public ConfigEntityRegistry(DataSchemaFactory dataSchemaFactory, InputStream configFileStream) {
    this.dataSchemaFactory = dataSchemaFactory;
    Entities entities = EntityRegistryUtils.readEntities(OBJECT_MAPPER, configFileStream);
    if (entities.getId() != null) {
      identifier = entities.getId();
    } else {
      identifier = "Unknown";
    }

    // Build Entity Specs
    entityNameToSpec = new HashMap<>();
    EntitySpecBuilder entitySpecBuilder = new EntitySpecBuilder();
    for (Entity entity : entities.getEntities()) {
      log.info("Discovered entity {} with aspects {}", entity.getName(),
              String.join(",", entity.getAspects()));
      List<AspectSpec> aspectSpecs = new ArrayList<>();
      aspectSpecs.add(buildAspectSpec(entity.getKeyAspect(), entitySpecBuilder));
      entity.getAspects().forEach(aspect -> aspectSpecs.add(buildAspectSpec(aspect, entitySpecBuilder)));

      EntitySpec entitySpec;
      Optional<DataSchema> entitySchema = dataSchemaFactory.getEntitySchema(entity.getName());
      if (!entitySchema.isPresent()) {
        entitySpec = entitySpecBuilder.buildConfigEntitySpec(entity.getName(), entity.getKeyAspect(), aspectSpecs);
      } else {
        entitySpec = entitySpecBuilder.buildEntitySpec(entitySchema.get(), aspectSpecs);
      }
      entityNameToSpec.put(entity.getName().toLowerCase(), entitySpec);
    }

    // Build Event Specs
    eventNameToSpec = new HashMap<>();
    if (entities.getEvents() != null) {
      for (Event event : entities.getEvents()) {
        EventSpec eventSpec = EntityRegistryUtils.buildEventSpec(event.getName(), dataSchemaFactory);
        eventNameToSpec.put(event.getName().toLowerCase(), eventSpec);
      }
    }
    entitySpecs = new ArrayList<>(entityNameToSpec.values());
    _aspectNameToSpec = populateAspectMap(entitySpecs);
  }

  @Override
  public String getIdentifier() {
    return this.identifier;
  }

  private AspectSpec buildAspectSpec(String aspectName, EntitySpecBuilder entitySpecBuilder) {
    Optional<DataSchema> aspectSchema = dataSchemaFactory.getAspectSchema(aspectName);
    Optional<Class> aspectClass = dataSchemaFactory.getAspectClass(aspectName);
    if (!aspectSchema.isPresent()) {
      throw new IllegalArgumentException(String.format("Aspect %s does not exist", aspectName));
    }
    return entitySpecBuilder.buildAspectSpec(aspectSchema.get(), aspectClass.get());
  }

  @Nonnull
  @Override
  public EntitySpec getEntitySpec(@Nonnull String entityName) {
    String lowercaseEntityName = entityName.toLowerCase();
    if (!entityNameToSpec.containsKey(lowercaseEntityName)) {
      throw new IllegalArgumentException(
          String.format("Failed to find entity with name %s in EntityRegistry", entityName));
    }
    return entityNameToSpec.get(lowercaseEntityName);
  }

  @Nonnull
  @Override
  public EventSpec getEventSpec(@Nonnull String eventName) {
    String lowerEventName = eventName.toLowerCase();
    if (!eventNameToSpec.containsKey(lowerEventName)) {
      throw new IllegalArgumentException(
          String.format("Failed to find event with name %s in EntityRegistry", eventName));
    }
    return eventNameToSpec.get(lowerEventName);
  }

  @Nonnull
  @Override
  public Map<String, EntitySpec> getEntitySpecs() {
    return entityNameToSpec;
  }

  @Nonnull
  @Override
  public Map<String, AspectSpec> getAspectSpecs() {
    return _aspectNameToSpec;
  }

  @Nonnull
  @Override
  public Map<String, EventSpec> getEventSpecs() {
    return eventNameToSpec;
  }

  @Nonnull
  @Override
  public AspectTemplateEngine getAspectTemplateEngine() {

    //TODO: add support for config based aspect templates
    return new AspectTemplateEngine();
  }
}
