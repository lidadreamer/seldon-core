package io.seldon.clustermanager.k8s;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.extensions.Deployment;
import io.fabric8.kubernetes.api.model.extensions.DeploymentBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.seldon.clustermanager.component.KubernetesManager;
import io.seldon.protos.DeploymentProtos.ClusterResourcesDef;
import io.seldon.protos.DeploymentProtos.DeploymentDef;
import io.seldon.protos.DeploymentProtos.PredictiveUnitDef;
import io.seldon.protos.DeploymentProtos.PredictorDef;


public class KubernetesManagerImpl implements KubernetesManager {

    private final static Logger logger = LoggerFactory.getLogger(KubernetesManagerImpl.class);

    private KubernetesClient kubernetesClient = null;

    @Override
    public void init() throws Exception {
        logger.info("init");

        String master = "http://localhost:8001/";
        Config config = new ConfigBuilder().withMasterUrl(master).build();

        try {
            kubernetesClient = new DefaultKubernetesClient(config);
            getNamespaceList(); // simple check to see if client works
            logger.info("Sucessfully passed namespace check test");
        } catch (KubernetesClientException e) {
            throw new Exception(e);
        }
    }

    @Override
    public void cleanup() throws Exception {
        logger.info("cleanup");
        if (kubernetesClient != null) {
            kubernetesClient.close();
        }
    }

    public List<String> getNamespaceList() {
        List<String> namespace_list = new ArrayList<>();
        NamespaceList namespaceList = kubernetesClient.namespaces().list();
        for (Namespace ns : namespaceList.getItems()) {
            namespace_list.add(ns.getMetadata().getName());
        }

        return namespace_list;
    }

    @Override
    public void createSeldonDeployment(DeploymentDef deploymentDef) {
        final String seldon_deployment_uniqueName = deploymentDef.getUniqueName();
        logger.info(String.format("Creating Seldon Deployment[%s]", seldon_deployment_uniqueName));

        PredictorDef predictorDef = deploymentDef.getPredictor();
        final String predictor_name = predictorDef.getName();

        List<PredictiveUnitDef> predictiveUnits = predictorDef.getPredictiveUnitsList();
        for (PredictiveUnitDef predictiveUnitDef : predictiveUnits) {
            final String predictive_unit_name = predictiveUnitDef.getName();
            logger.info(String.format("Deploying predictiveUnit[%s] for seldonDeployment[%s]", predictive_unit_name,
                    seldon_deployment_uniqueName));

            ClusterResourcesDef clusterResourcesDef = predictiveUnitDef.getClusterResources();
            Deployment deployment = createKubernetesDeployement(seldon_deployment_uniqueName, kubernetesClient, clusterResourcesDef);
            
            String msg = (deployment != null) ? deployment.getMetadata().getName() : null;
            logger.info(String.format("Created kubernetes delployment [%s]", msg));

        }

    }

    @Override
    public void deleteSeldonDeployment(DeploymentDef deploymentDef) {
        
        final String seldon_deployment_uniqueName = deploymentDef.getUniqueName();
        logger.info(String.format("Deleting Seldon Deployment[%s]", seldon_deployment_uniqueName));
        final String namespace_name = "default"; // TODO change this!
        
        Deployment deployment = kubernetesClient.extensions().deployments().inNamespace(namespace_name).withName(seldon_deployment_uniqueName).get();
        String msg = (deployment != null) ? deployment.getMetadata().getName() : null;
        logger.info(String.format("Deleted kubernetes delployment [%s]", msg));

        io.fabric8.kubernetes.api.model.extensions.ReplicaSetList rslist = kubernetesClient.extensions().replicaSets().inNamespace(namespace_name)
                .withLabel("seldon-app", seldon_deployment_uniqueName).list();
        kubernetesClient.resource(deployment).delete();
        for (io.fabric8.kubernetes.api.model.extensions.ReplicaSet rs : rslist.getItems()) {
            kubernetesClient.resource(rs).delete();
            String rsmsg = (rs != null) ? rs.getMetadata().getName() : null;
            logger.info(String.format("Deleted kubernetes replicaSet [%s]", rsmsg));
        }
    }

    /**
     * Helper method to create a kubernetes deployment
     */
    private static Deployment createKubernetesDeployement(String seldon_deployment_uniqueName, KubernetesClient kubernetesClient, ClusterResourcesDef clusterResourcesDef) {
            final int replica_number = clusterResourcesDef.getReplicas();
            final int container_port = 80; // TODO change this!
            final String namespace_name = "default"; // TODO change this!
            final String image_name_and_version = clusterResourcesDef.getImage()+":"+clusterResourcesDef.getVersion();

            //@formatter:off
            Deployment deployment = new DeploymentBuilder()
                    .withNewMetadata().withName(seldon_deployment_uniqueName).endMetadata()
                    .withNewSpec().withReplicas(replica_number)
                        .withNewTemplate()
                            .withNewMetadata().addToLabels("seldon-app", seldon_deployment_uniqueName).endMetadata()
                            .withNewSpec().addNewContainer().withName("seldon-container").withImage(image_name_and_version)
                                .addNewPort().withContainerPort(container_port).endPort().endContainer()
                            .endSpec()
                        .endTemplate()
                    .endSpec().build();
            //@formatter:on

            deployment = kubernetesClient.extensions().deployments().inNamespace(namespace_name).create(deployment);
        return deployment;
    }
}