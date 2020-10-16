package org.carlspring.strongbox.gremlin.adapters;

import static org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality.single;
import static org.carlspring.strongbox.gremlin.dsl.EntityTraversalUtils.extractObject;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.inject.Inject;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.carlspring.strongbox.artifact.ArtifactTag;
import org.carlspring.strongbox.artifact.coordinates.GenericArtifactCoordinates;
import org.carlspring.strongbox.db.schema.Edges;
import org.carlspring.strongbox.db.schema.Vertices;
import org.carlspring.strongbox.domain.Artifact;
import org.carlspring.strongbox.domain.ArtifactIdGroup;
import org.carlspring.strongbox.domain.ArtifactIdGroupEntity;
import org.carlspring.strongbox.gremlin.dsl.EntityTraversal;
import org.carlspring.strongbox.gremlin.dsl.EntityTraversalUtils;
import org.carlspring.strongbox.gremlin.dsl.__;
import org.springframework.stereotype.Component;

import static org.carlspring.strongbox.db.schema.Properties.UUID;
import static org.carlspring.strongbox.db.schema.Properties.NAME;
import static org.carlspring.strongbox.db.schema.Properties.STORAGE_ID;
import static org.carlspring.strongbox.db.schema.Properties.REPOSITORY_ID;
import static org.carlspring.strongbox.db.schema.Properties.TAG_NAME;

/**
 * @author sbespalov
 */
@Component
public class ArtifactIdGroupAdapter implements VertexEntityTraversalAdapter<ArtifactIdGroup>
{

    @Inject
    private ArtifactAdapter artifactAdapter;

    @Override
    public String label()
    {
        return Vertices.ARTIFACT_ID_GROUP;
    }

    public EntityTraversal<Vertex, ArtifactIdGroup> fold(Optional<Class<? extends GenericArtifactCoordinates>> layoutArtifactCoordinatesClass,
                                                         Optional<ArtifactTag> optionalTag)
    {
        EntityTraversal<Vertex, Vertex> artifactsTraversal = optionalTag.map(ArtifactTag::getName)
                                                                        .map(tagName -> __.outE(Edges.ARTIFACT_GROUP_HAS_TAGGED_ARTIFACTS)
                                                                                          .has(TAG_NAME, tagName)
                                                                                          .inV())
                                                                        .orElse(__.outE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS)
                                                                                  .inV());

        return __.<Vertex, Object>project("id", UUID, STORAGE_ID, REPOSITORY_ID, NAME, "artifacts")
                 .by(__.id())
                 .by(__.enrichPropertyValue(UUID))
                 .by(__.enrichPropertyValue(STORAGE_ID))
                 .by(__.enrichPropertyValue(REPOSITORY_ID))
                 .by(__.enrichPropertyValue(NAME))
                 .by(artifactsTraversal.dedup()
                                       .map(artifactAdapter.fold(layoutArtifactCoordinatesClass))
                                       .map(EntityTraversalUtils::castToObject)
                                       .fold())
                 .map(this::map);
    }

    @Override
    public EntityTraversal<Vertex, ArtifactIdGroup> fold()
    {
        return fold(Optional.empty(), Optional.empty());
    }

    private ArtifactIdGroup map(Traverser<Map<String, Object>> t)
    {
        ArtifactIdGroupEntity result = new ArtifactIdGroupEntity(extractObject(String.class, t.get().get(STORAGE_ID)),
                extractObject(String.class, t.get().get(REPOSITORY_ID)), extractObject(String.class, t.get().get(NAME)));
        result.setNativeId(extractObject(Long.class, t.get().get("id")));
        result.setUuid(extractObject(String.class, t.get().get(UUID)));
        Collection<Artifact> artifacts = (Collection<Artifact>) t.get().get("artifacts");
        artifacts.stream().forEach(result::addArtifact);

        return result;
    }

    @Override
    public UnfoldEntityTraversal<Vertex, Vertex> unfold(ArtifactIdGroup entity)
    {
        String storedArtifactIdGroup = Vertices.ARTIFACT_ID_GROUP + ":" + java.util.UUID.randomUUID();
        EntityTraversal<Vertex, Vertex> connectArtifacstTraversal = __.identity();
        for (Artifact artifact : entity.getArtifacts())
        {
            connectArtifacstTraversal = connectArtifacstTraversal.V(artifact)
                                                                 .saveV(artifact.getUuid(),
                                                                        artifactAdapter.unfold(artifact));
            
            connectArtifacstTraversal.choose(__.inE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS),
                                             __.identity(),
                                             __.addE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS)
                                             .from(__.<Vertex, Vertex>select(storedArtifactIdGroup).unfold())
                                             .inV());
            
            connectArtifacstTraversal = connectArtifacstTraversal.sideEffect(__.inE(Edges.ARTIFACT_GROUP_HAS_TAGGED_ARTIFACTS).drop());
            for (ArtifactTag artifactTag : artifact.getTagSet())
            {
                connectArtifacstTraversal = connectArtifacstTraversal.addE(Edges.ARTIFACT_GROUP_HAS_TAGGED_ARTIFACTS)
                                                                     .from(__.<Vertex, Vertex>select(storedArtifactIdGroup).unfold())
                                                                     .property(TAG_NAME, artifactTag.getName())
                                                                     .inV();

            }
            connectArtifacstTraversal = connectArtifacstTraversal.inE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS).outV();
        }

        
        EntityTraversal<Vertex, Vertex> unfoldTraversal = __.<Vertex, Vertex>map(unfoldArtifactGroup(entity))
                                                            .store(storedArtifactIdGroup)
                                                            .flatMap(connectArtifacstTraversal)
                                                            .fold().map(t -> t.get().iterator().next());

        return new UnfoldEntityTraversal<>(Vertices.ARTIFACT_ID_GROUP, entity, unfoldTraversal);
    }

    private EntityTraversal<Vertex, Vertex> unfoldArtifactGroup(ArtifactIdGroup entity)
    {
        EntityTraversal<Vertex, Vertex> t = __.identity();
        // Skip update as ArtifactIdGroup assumed to be immutable
        if (entity.getNativeId() != null)
        {
            return t;
        }

        if (entity.getStorageId() != null)
        {
            t = t.property(single, STORAGE_ID, entity.getStorageId());
        }
        if (entity.getRepositoryId() != null)
        {
            t = t.property(single, REPOSITORY_ID, entity.getRepositoryId());
        }
        if (entity.getName() != null)
        {
            t = t.property(single, NAME, entity.getName());
        }

        return t;
    }

    @Override
    public EntityTraversal<Vertex, Element> cascade()
    {
        return __.<Vertex>aggregate("x")
                 .optional(__.outE(Edges.ARTIFACT_GROUP_HAS_ARTIFACTS)
                             .inV()
                             .flatMap(artifactAdapter.cascade()))
                 .select("x")
                 .unfold();
    }

}