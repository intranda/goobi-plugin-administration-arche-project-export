package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.goobi.api.rest.ArcheAPI;
import org.goobi.api.rest.TransactionInfo;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.GoobiProperty.PropertyOwnerType;
import org.goobi.beans.Project;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;
import org.goobi.production.properties.DisplayProperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import de.sub.goobi.persistence.managers.PropertyManager;
import jakarta.faces.model.SelectItem;
import jakarta.ws.rs.client.Client;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class ArcheProjectExportAdministrationPlugin implements IAdministrationPlugin {

    private static final long serialVersionUID = 8616559554562036309L;

    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    @Getter
    private String title = "intranda_administration_arche_project_export";

    @Getter
    private String value;

    @Getter
    private PluginType type = PluginType.Administration;

    @Getter
    private String gui = "/uii/plugin_administration_arche_project_export.xhtml";

    private List<Project> possibleProjects;

    @Getter
    @Setter
    private Project selectedProject;

    @Getter
    private List<DisplayProperty> displayProperties;

    private static final String IDENTIFIER_PREFIX = "https://id.acdh.oeaw.ac.at/";

    private String archeUserName;
    private String archePassword;
    private String archeApiUrl;
    private String archeUrlPropertyName;
    private String placeholderImage;

    private XMLConfiguration config;

    /*
    
    docker stop acdh-repo
    docker container rm acdh-repo
    docker run --name acdh-repo -p 80:80 -e CFG_BRANCH=arche -e ADMIN_PSWD='admin' -d acdhch/arche
    
    docker start acdh-repo
    docker exec -ti acdh-repo /bin/bash
    
     */

    /**
     * Constructor
     */
    public ArcheProjectExportAdministrationPlugin() {

    }

    public Integer getProjectId() {
        if (selectedProject != null) {
            return selectedProject.getId();
        } else {
            return null;
        }
    }

    public void setProjectId(Integer inProjektAuswahl) {
        if (inProjektAuswahl != null && inProjektAuswahl.intValue() != 0) {
            try {
                selectedProject = ProjectManager.getProjectById(inProjektAuswahl);
            } catch (DAOException e) {
                Helper.setFehlerMeldung("Projekt kann nicht zugewiesen werden", "");
                log.error(e);
            }
            displayProperties = new ArrayList<>();
            // get configured property names
            config = ConfigPlugins.getPluginConfig(title);
            config.setExpressionEngine(new XPathExpressionEngine());

            archeUserName = config.getString("/archeUserName");
            archePassword = config.getString("/archePassword");
            archeApiUrl = config.getString("/archeApiUrl");
            archeUrlPropertyName = config.getString("/archeUrlPropertyName");
            placeholderImage = config.getString("/placeholderImage");

            for (HierarchicalConfiguration hc : config.configurationsAt("/property")) {
                String propertyName = hc.getString("@name");
                String defaultValue = hc.getString("@default");
                String displayType = hc.getString("@type", "text");

                // check, if property exists
                GoobiProperty property = null;
                for (GoobiProperty gp : selectedProject.getProperties()) {
                    if (gp.getPropertyName().equals(propertyName)) {
                        property = gp;
                        break;
                    }
                }
                // create, if needed
                if (property == null) {

                    property = new GoobiProperty(PropertyOwnerType.PROJECT);
                    property.setOwner(selectedProject);
                    property.setPropertyName(propertyName);
                    selectedProject.getProperties().add(property);
                }

                // set default value
                if (StringUtils.isBlank(property.getPropertyValue()) && StringUtils.isNotBlank(defaultValue)) {
                    property.setPropertyValue(defaultValue);
                }

                DisplayProperty pp = new DisplayProperty();
                pp.setName(propertyName);
                pp.setContainer("0");
                pp.setType(org.goobi.production.properties.Type.getTypeByName(displayType));
                pp.setValidation(config.getString("@validation"));

                pp.setPattern(config.getString("@pattern", "dd.MM.yyyy"));

                displayProperties.add(pp);
                pp.setProzesseigenschaft(property);
                pp.setValue(property.getPropertyValue());
                pp.setContainer(property.getContainer());

                for (HierarchicalConfiguration selectItem : hc.configurationsAt("/select")) {
                    pp.getPossibleValues().add(new SelectItem(selectItem.getString("@value"), selectItem.getString("@label")));
                }
            }
        }
    }

    public List<Project> getPossibleProjects() {
        if (possibleProjects == null) {
            log.trace("project list is not initialized, load them from database");
            possibleProjects = ProjectManager.getAllProjects();
        }
        return possibleProjects;
    }

    public void exportProject() {

        // save properties
        try {
            for (DisplayProperty dp : displayProperties) {
                dp.transfer();
            }

            ProjectManager.saveProject(selectedProject);
        } catch (DAOException e) {
            log.error(e);
        }
        // create ttl

        // check if configured property exists
        // if yes: use this as id and create PATCH request
        // if not: create new resource with api url and store location after request

        String location = null;
        //  check if project has a property for the arche-id
        // if yes -> PATCH
        // if no: POST
        if (StringUtils.isNotBlank(archeUrlPropertyName)) {
            for (GoobiProperty gp : selectedProject.getProperties()) {
                if (gp.getPropertyName().equals(archeUrlPropertyName)) {
                    location = gp.getPropertyValue();
                }
            }
        }
        if (location != null) {
            Resource resource = createTopCollectionDocument(location);
            saveTurtleOnDisc(resource);
            if (StringUtils.isNotBlank(archeApiUrl)) {
                updateExistingResource(resource, location);
            }
        } else {
            Resource resource = createTopCollectionDocument(archeApiUrl);

            // option to store ttl in local folder
            saveTurtleOnDisc(resource);

            // option to upload ttl into arche
            if (StringUtils.isNotBlank(archeApiUrl)) {

                ingestNewResource(resource);
            }
        }
    }

    private void updateExistingResource(Resource resource, String location) {
        try (Client client = ArcheAPI.getClient(archeUserName, archePassword)) {
            TransactionInfo ti = ArcheAPI.startTransaction(client, archeApiUrl);
            ArcheAPI.updateMetadata(client, location, ti, resource);
            ArcheAPI.finishTransaction(client, archeApiUrl, ti);
        }
    }

    private void ingestNewResource(Resource resource) {
        try (Client client = ArcheAPI.getClient(archeUserName, archePassword)) {
            TransactionInfo ti = ArcheAPI.startTransaction(client, archeApiUrl);
            String collectionUri = ArcheAPI.uploadMetadata(client, archeApiUrl, ti, resource);

            // store collectionUri in archeUrlPropertyName
            GoobiProperty archeUrl = new GoobiProperty(PropertyOwnerType.PROJECT);
            archeUrl.setPropertyName(archeUrlPropertyName);
            archeUrl.setPropertyValue(collectionUri);
            archeUrl.setOwner(selectedProject);
            PropertyManager.saveProperty(archeUrl);
            selectedProject.getProperties().add(archeUrl);

            System.out.println("top collection: " + collectionUri);
            // add image resource
            Path imagePath = Paths.get(placeholderImage);
            Resource image = createPlaceholderImageResource(collectionUri, imagePath.getFileName().toString());
            String imageUrl = ArcheAPI.uploadMetadata(client, archeApiUrl, ti, image);
            // upload image
            ArcheAPI.uploadBinary(client, imageUrl, ti, imagePath);
            ArcheAPI.finishTransaction(client, archeApiUrl, ti);
        }
    }

    private void saveTurtleOnDisc(Resource resource) {
        String exportFolder = config.getString("/exportFolder");
        if (StringUtils.isNotBlank(exportFolder)) {
            if (!exportFolder.endsWith("/")) {
                exportFolder = exportFolder + "/";
            }
            try (OutputStream out = new FileOutputStream(exportFolder + selectedProject.getTitel() + ".ttl")) {
                RDFDataMgr.write(out, resource.getModel(), RDFFormat.TURTLE_PRETTY);
            } catch (IOException e) {
                log.error(e);
            }
        }
    }

    private Resource createPlaceholderImageResource(String collectionUri, String filename) {

        String languageCode = "en";
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");

        String topCollectionIdentifier = IDENTIFIER_PREFIX + selectedProject.getTitel();

        String resourceIdentifier = topCollectionIdentifier + "/" + filename;

        Resource resource =
                model.createResource(archeApiUrl, model.createResource(model.getNsPrefixURI("acdh") + "Resources"));

        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), filename, languageCode);
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                model.createResource(resourceIdentifier));
        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"),
                model.createResource(topCollectionIdentifier));

        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCategory"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archecategory/image"));

        writePropertyValue(languageCode, model, resource, "hasOwner");
        writePropertyValue(languageCode, model, resource, "hasMetadataCreator");
        writePropertyValue(languageCode, model, resource, "hasCurator");
        writePropertyValue(languageCode, model, resource, "hasLicensor");
        writePropertyValue(languageCode, model, resource, "hasRightsHolder");
        writePropertyValue(languageCode, model, resource, "hasDepositor");

        return resource;
    }

    private Resource createTopCollectionDocument(String resourceIdentifier) {
        String languageCode = "en";
        Model model = ModelFactory.createDefaultModel();

        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");

        // collection name
        String topCollectionIdentifier = IDENTIFIER_PREFIX + selectedProject.getTitel();
        Resource projectResource =
                model.createResource(resourceIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "TopCollection"));
        //        hasTitle -> project name
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), selectedProject.getTitel(), languageCode);
        //        hasIdentifier -> topCollectionIdentifier
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                model.createResource(topCollectionIdentifier));

        //        hasPid ->  get handle from property or leave it free
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if ("Handle".equalsIgnoreCase(gp.getPropertyName())) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasPid"),
                        gp.getPropertyValue(), XSDDatatype.XSDanyURI);
                break;
            }
        }

        //        hasUrl -> viewer root url https://viewer.acdh.oeaw.ac.at/viewer
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"), config.getString("/viewerUrl"),
                XSDDatatype.XSDanyURI);

        //        hasDescription
        writePropertyValue(languageCode, model, projectResource, "hasDescription");

        //        hasLifeCycleStatus ->
        // project active: set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active
        // Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        if (selectedProject.getProjectIsArchived().booleanValue()) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active"));
        } else {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));
        }
        //        hasUsedSoftware -> Goobi
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUsedSoftware"), "Goobi");

        //        hasContact
        writePropertyValue(languageCode, model, projectResource, "hasContact");

        //        hasContributor
        writePropertyValue(languageCode, model, projectResource, "hasContributor");

        //        hasDigitisingAgent
        writePropertyValue(languageCode, model, projectResource, "hasDigitisingAgent");

        //        hasMetadataCreator
        writePropertyValue(languageCode, model, projectResource, "hasMetadataCreator");

        //        hasRelatedDiscipline
        writePropertyValue(languageCode, model, projectResource, "hasRelatedDiscipline");

        //        hasSubject
        writePropertyValue(languageCode, model, projectResource, "hasSubject");

        String query =
                "select min(value), max(value) from metadata where name = 'PublicationYear' and processid in (select ProzesseId from prozesse where ProjekteID = (Select projekteid from projekte where titel='"
                        + selectedProject.getTitel() + "')) and value REGEXP '^[0-9]+$' group by name;";
        @SuppressWarnings("unchecked")
        List<Object> results = ProcessManager.runSQL(query);
        if (!results.isEmpty()) {
            Object[] row = (Object[]) results.get(0);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageStartDate"), String.valueOf(row[0]),
                    XSDDatatype.XSDdate);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageEndDate"), String.valueOf(row[1]),
                    XSDDatatype.XSDdate);
        }

        //        hasOwner
        writePropertyValue(languageCode, model, projectResource, "hasOwner");

        //        hasRightsHolder
        writePropertyValue(languageCode, model, projectResource, "hasRightsHolder");

        //        hasLicensor
        writePropertyValue(languageCode, model, projectResource, "hasLicensor");

        //        hasLicense
        writePropertyValue(languageCode, model, projectResource, "hasLicense");

        //        hasCreatedStartDate
        if (selectedProject.getStartDate() != null) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedStartDate"),
                    sdf.format(selectedProject.getStartDate()), XSDDatatype.XSDdate);
        }

        //        hasCreatedEndDate
        if (selectedProject.getEndDate() != null) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedStartDate"),
                    sdf.format(selectedProject.getEndDate()), XSDDatatype.XSDdate);
        }

        //        hasDepositor
        writePropertyValue(languageCode, model, projectResource, "hasDepositor");

        //        hasCurator
        writePropertyValue(languageCode, model, projectResource, "hasCurator");

        return projectResource;
    }

    private void writePropertyValue(String languageCode, Model model, Resource projectResource, String fieldName) {
        String propertyName = config.getString("/property[@ttlField='" + fieldName + "']/@name");
        String propertyType = config.getString("/property[@ttlField='" + fieldName + "']/@ttlType", "literal");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                if ("literal".equalsIgnoreCase(propertyType)) {
                    projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), fieldName),
                            gp.getPropertyValue(), languageCode);
                } else {
                    projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), fieldName),
                            model.createResource(gp.getPropertyValue()));
                }
            }
        }
    }
}
