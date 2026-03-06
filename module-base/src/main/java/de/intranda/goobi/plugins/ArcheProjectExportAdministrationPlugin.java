package de.intranda.goobi.plugins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFFormat;
import org.goobi.api.ArcheConfiguration;
import org.goobi.api.rest.ArcheAPI;
import org.goobi.api.rest.TransactionInfo;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.GoobiProperty.PropertyOwnerType;
import org.goobi.beans.Project;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;
import org.goobi.production.properties.DisplayProperty;

import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.sub.goobi.persistence.managers.ProjectManager;
import io.goobi.workflow.api.connection.HttpUtils;
import io.goobi.workflow.api.vocabulary.APIException;
import io.goobi.workflow.api.vocabulary.VocabularyAPIManager;
import io.goobi.workflow.api.vocabulary.helper.APIExceptionExtractor;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabulary;
import io.goobi.workflow.api.vocabulary.helper.ExtendedVocabularyRecord;
import jakarta.faces.model.SelectItem;
import jakarta.ws.rs.ProcessingException;
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
    private PluginType type = PluginType.Administration;

    @Getter
    private String gui = "/uii/plugin_administration_arche_project_export.xhtml";

    private List<Project> possibleProjects;

    @Getter
    @Setter
    private Project selectedProject;

    @Getter
    private List<ArcheProperty> displayProperties;

    private ArcheConfiguration archeConfiguration;

    @Getter
    private List<SelectItem> possibleLanguages;

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
        archeConfiguration = new ArcheConfiguration(title);
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

            possibleLanguages = new ArrayList<>();
            possibleLanguages.add(new SelectItem(null, ""));
            for (HierarchicalConfiguration hc : archeConfiguration.getConfig().configurationsAt("/languages/language")) {
                String label = hc.getString("@label");
                String value = hc.getString("@value");
                possibleLanguages.add(new SelectItem(value, label));
            }

            // get configured property names
            for (HierarchicalConfiguration hc : archeConfiguration.getConfig().configurationsAt("/project/property")) {
                String propertyName = hc.getString("@name");
                String defaultValue = hc.getString("@default");
                String displayType = hc.getString("@type", "text");

                // check, if property exists
                List<GoobiProperty> properties = new ArrayList<>();
                for (GoobiProperty gp : selectedProject.getProperties()) {
                    if (gp.getPropertyName().equals(propertyName)) {
                        properties.add(gp);
                    }
                }
                // create, if needed
                if (properties.isEmpty()) {

                    GoobiProperty property = new GoobiProperty(PropertyOwnerType.PROJECT);
                    property.setOwner(selectedProject);
                    property.setPropertyName(propertyName);
                    selectedProject.getProperties().add(property);
                    properties.add(property);
                }
                for (GoobiProperty property : properties) {

                    // set default value
                    if (StringUtils.isBlank(property.getPropertyValue()) && StringUtils.isNotBlank(defaultValue)) {
                        property.setPropertyValue(defaultValue);
                    }

                    ArcheProperty pp = new ArcheProperty();
                    pp.setName(propertyName);
                    pp.setContainer("0");
                    pp.setType(org.goobi.production.properties.Type.getTypeByName(displayType));
                    pp.setValidation(hc.getString("@validation"));

                    pp.setPattern(hc.getString("@pattern", "dd.MM.yyyy"));
                    pp.setRepeatable(hc.getBoolean("@repeatable", false));
                    pp.setLanguageField(hc.getBoolean("@languageField", false));

                    displayProperties.add(pp);
                    pp.setProzesseigenschaft(property);
                    String value = property.getPropertyValue();
                    if (pp.isLanguageField() && StringUtils.isNotBlank(value)) {
                        String[] parts = value.split("@@@");
                        pp.setValue(parts[0]);
                        if (parts.length > 1) {
                            pp.setSelectedLanguage(parts[1]);
                        }
                    } else {
                        pp.setValue(value);
                    }
                    pp.setContainer(property.getContainer());

                    if ("vocabularyreference".equals(displayType)) {
                        String vocabularyName = hc.getString("/vocabulary/@name");
                        String labelField = hc.getString("/vocabulary/@label");
                        String valueField = hc.getString("/vocabulary/@value");
                        initializeProperty(vocabularyName, labelField, valueField, pp);
                    }

                    for (HierarchicalConfiguration selectItem : hc.configurationsAt("/select")) {
                        pp.getPossibleValues().add(new SelectItem(selectItem.getString("@value"), selectItem.getString("@label")));
                    }
                }
            }
        }
    }

    private static void initializeProperty(String vocabularyName, String labelField, String valueField, DisplayProperty pp) {
        try {
            ExtendedVocabulary currentVocabulary = VocabularyAPIManager.getInstance().vocabularies().findByName(vocabularyName);

            List<ExtendedVocabularyRecord> recordList = VocabularyAPIManager.getInstance()
                    .vocabularyRecords()
                    .list(currentVocabulary.getId())
                    .all()
                    .request()
                    .getContent();

            pp.getPossibleValues().clear();
            for (ExtendedVocabularyRecord rec : recordList) {
                String label = rec.getFieldForDefinitionName(labelField).get().getFieldValue();
                String value = rec.getFieldForDefinitionName(valueField).get().getFieldValue();

                pp.getPossibleValues().add(new SelectItem(value, label));
            }
        } catch (APIException e) {
            APIExceptionExtractor extractor = new APIExceptionExtractor(e);
            String message = "Failed to load vocabulary \"" + vocabularyName + "\" records, Reason: \n"
                    + extractor.getLocalizedMessage(Helper.getSessionLocale());
            log.error(message, e);
            Helper.setFehlerMeldung(message, e.getMessage());
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

        // abort, if language field is not selected
        for (ArcheProperty dp : displayProperties) {
            if (dp.isLanguageField() && StringUtils.isBlank(dp.getSelectedLanguage())) {
                Helper.setFehlerMeldung("No language selected.");
                return;
            }
        }

        // save properties
        try {
            for (ArcheProperty dp : displayProperties) {
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

        //        String location = null;
        //  check if project has a property for the arche-id
        // if yes -> PATCH
        // if no: POST
        //        if (archeConfiguration.isEnableArcheIngest(prodIngest)) {
        //            for (GoobiProperty gp : selectedProject.getProperties()) {
        //                if (gp.getPropertyName().equals(archeConfiguration.getArcheUrlPropertyName(prodIngest))) {
        //                    location = gp.getPropertyValue();
        //                    break;
        //                }
        //            }
        //        }
        //        if (location != null) {
        //
        //            Resource resource = createTopCollectionDocument(location);
        //            saveTurtleOnDisc(resource);
        //            if (archeConfiguration.isEnableArcheIngest(prodIngest)) {
        //                try {
        //                    updateExistingResource(resource, location, prodIngest);
        //                } catch (ProcessingException e) {
        //                    Helper.setFehlerMeldung("Cannot reach arche API");
        //                }
        //            }
        //        } else {
        String topCollectionIdentifier = null;
        if (StringUtils.isNotBlank(selectedProject.getProjectIdentifier())) {
            topCollectionIdentifier = archeConfiguration.getIdentifierPrefix() + selectedProject.getProjectIdentifier().replace(" ", "_");
        } else {
            topCollectionIdentifier = archeConfiguration.getIdentifierPrefix() + selectedProject.getTitel().replace(" ", "_");
        }

        Resource resource = createTopCollectionDocument(topCollectionIdentifier, topCollectionIdentifier);
        // option to store ttl in local folder
        saveTurtleOnDisc(resource);

        Resource validationResource = createTopCollectionDocument(topCollectionIdentifier, archeConfiguration.getArcheApiUrl());
        // option to upload ttl into arche
        if (archeConfiguration.isEnableArcheIngest()) {
            try {
                ingestNewResource(validationResource);
            } catch (ProcessingException e) {
                Helper.setFehlerMeldung("Cannot reach arche API");
            }
        }
    }
    //    }
    //
    //    private boolean updateExistingResource(Resource resource, String location, boolean prodIngest) {
    //        try (Client client = ArcheAPI.getClient(archeConfiguration.getArcheUserName(prodIngest), archeConfiguration.getArchePassword(prodIngest))) {
    //            TransactionInfo ti = ArcheAPI.startTransaction(client, archeConfiguration.getArcheApiUrl(prodIngest));
    //            if (ArcheAPI.updateMetadata(client, location, archeConfiguration.getArcheApiUrl(prodIngest), resource, ti) == null) {
    //                ArcheAPI.cancelTransaction(client, archeConfiguration.getArcheApiUrl(prodIngest), ti);
    //                return false;
    //            }
    //            ArcheAPI.finishTransaction(client, archeConfiguration.getArcheApiUrl(prodIngest), ti);
    //            return true;
    //        }
    //    }

    private void ingestNewResource(Resource resource) {
        try (Client client = ArcheAPI.getClient(archeConfiguration.getArcheUserName(), archeConfiguration.getArchePassword())) {
            TransactionInfo ti = ArcheAPI.startTransaction(client, archeConfiguration.getArcheApiUrl());
            String collectionUri = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(), ti, resource);
            if (collectionUri != null) {
                ArcheAPI.cancelTransaction(client, collectionUri, ti);
                Helper.setMeldung("Arche validation successful");
                //            if (collectionUri == null) {
                //                ArcheAPI.cancelTransaction(client, collectionUri, ti);
                //                return;
                //            }
                //
                //            // store collectionUri in archeUrlPropertyName
                //            GoobiProperty archeUrl = new GoobiProperty(PropertyOwnerType.PROJECT);
                //            archeUrl.setPropertyName(archeConfiguration.getArcheUrlPropertyName(prodIngest));
                //            archeUrl.setPropertyValue(collectionUri);
                //            archeUrl.setOwner(selectedProject);
                //            PropertyManager.saveProperty(archeUrl);
                //            selectedProject.getProperties().add(archeUrl);
                //
                //            // add image resource
                //            Path imagePath = Paths.get(archeConfiguration.getPlaceholderImage());
                //            Resource image = createPlaceholderImageResource(collectionUri, imagePath.getFileName().toString(), prodIngest);
                //            String imageUrl = ArcheAPI.uploadMetadata(client, archeConfiguration.getArcheApiUrl(prodIngest), ti, image);
                //            // upload image
                //            if (imageUrl != null) {
                //                ArcheAPI.uploadBinary(client, imageUrl, ti, imagePath);
                //                ArcheAPI.finishTransaction(client, archeConfiguration.getArcheApiUrl(prodIngest), ti);
                //            }
            }
        }
    }

    private void saveTurtleOnDisc(Resource resource) {
        String exportFolder = archeConfiguration.getExportFolder();
        if (StringUtils.isNotBlank(exportFolder)) {
            if (!exportFolder.endsWith("/")) {
                exportFolder = exportFolder + "/";
            }
            if (selectedProject.getProjectIdentifier() != null) {
                exportFolder = exportFolder + selectedProject.getProjectIdentifier().replace(" ", "_") + ".ttl";
            } else {
                exportFolder = exportFolder + selectedProject.getTitel().replace(" ", "_") + ".ttl";
            }

            try (OutputStream out = new FileOutputStream(exportFolder)) {
                RDFDataMgr.write(out, resource.getModel(), RDFFormat.TURTLE_PRETTY);
            } catch (IOException e) {
                log.error(e);
            }
        }
    }

    //    private Resource createPlaceholderImageResource(String collectionUri, String filename, boolean prodIngest) {
    //
    //        String languageCode = "en";
    //        Model model = ModelFactory.createDefaultModel();
    //        model.setNsPrefix("acdh", archeConfiguration.getArcheNamespace());
    //
    //        String topCollectionIdentifier = archeConfiguration.getIdentifierPrefix() + selectedProject.getTitel();
    //
    //        String resourceIdentifier = topCollectionIdentifier + "/" + filename;
    //
    //        Resource resource =
    //                model.createResource(archeConfiguration.getArcheApiUrl(prodIngest), model.createResource(model.getNsPrefixURI("acdh") + "Resources"));
    //
    //        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), filename, languageCode);
    //        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
    //                model.createResource(resourceIdentifier));
    //        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "isPartOf"),
    //                model.createResource(topCollectionIdentifier));
    //
    //        resource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCategory"),
    //                model.createResource("https://vocabs.acdh.oeaw.ac.at/archecategory/image"));
    //
    //        writePropertyValue(languageCode, model, resource, "hasOwner");
    //        writePropertyValue(languageCode, model, resource, "hasMetadataCreator");
    //        writePropertyValue(languageCode, model, resource, "hasCurator");
    //        writePropertyValue(languageCode, model, resource, "hasLicensor");
    //        writePropertyValue(languageCode, model, resource, "hasRightsHolder");
    //        writePropertyValue(languageCode, model, resource, "hasDepositor");
    //
    //        return resource;
    //    }

    private Resource createTopCollectionDocument(String topCollectionIdentifier, String resourceIdentifier) {
        Model model = ModelFactory.createDefaultModel();

        model.setNsPrefix("acdh", archeConfiguration.getArcheNamespace());

        // collection name

        Resource projectResource =
                model.createResource(resourceIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "TopCollection"));
        //        hasTitle -> project name
        //    projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), selectedProject.getTitel(), languageCode);
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

        //        hasLifeCycleStatus ->
        // project active: set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active
        // Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        if (selectedProject.getProjectIsArchived().booleanValue()) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));
        } else {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active"));
        }
        //        hasUsedSoftware -> Goobi
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUsedSoftware"), "Goobi");

        String query =
                "select min(value), max(value) from metadata where name = 'PublicationYear' and processid in (select ProzesseId from prozesse where ProjekteID ="
                        + selectedProject.getId() + ") and value REGEXP '^[0-9]+$' group by name;";
        @SuppressWarnings("unchecked")
        List<Object> results = ProcessManager.runSQL(query);
        if (!results.isEmpty()) {
            Object[] row = (Object[]) results.get(0);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageStartDate"), String.valueOf(row[0]),
                    XSDDatatype.XSDdate);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageEndDate"), String.valueOf(row[1]),
                    XSDDatatype.XSDdate);
        }
        //        hasCreatedStartDate
        if (selectedProject.getStartDate() != null) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedStartDate"),
                    sdf.format(selectedProject.getStartDate()), XSDDatatype.XSDdate);
        }

        //        hasCreatedEndDate
        if (selectedProject.getEndDate() != null) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedEndDate"),
                    sdf.format(selectedProject.getEndDate()), XSDDatatype.XSDdate);
        }

        for (ArcheProperty gp : displayProperties) {
            String propertyName = gp.getName();

            try {
                HierarchicalConfiguration conf = archeConfiguration.getConfig().configurationAt("/project/property[@name='" + propertyName + "']");
                if (conf != null) {
                    String ttlFieldName = conf.getString("@ttlField");

                    String ttlType = conf.getString("@ttlType", "literal");
                    if ("literal".equalsIgnoreCase(ttlType)) {
                        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), ttlFieldName),
                                gp.getValue(), gp.getSelectedLanguage());

                    } else if ("uri".equals(ttlType)) {
                        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), ttlFieldName), gp.getValue(),
                                XSDDatatype.XSDanyURI);
                    } else {
                        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), ttlFieldName),
                                model.createResource(gp.getValue()));
                    }
                }
            } catch (IllegalArgumentException e) {
                // property is not configured, skip it
            }
        }

        return projectResource;

    }

    public void updateVocabulary(DisplayProperty property) {
        HierarchicalConfiguration hc = archeConfiguration.getConfig().configurationAt("/project/property[@name='" + property.getName() + "']");
        String vocabularyName = hc.getString("/vocabulary/@name");
        String labelField = hc.getString("/vocabulary/@label");
        String valueField = hc.getString("/vocabulary/@value");

        try {
            long vocabularyId = VocabularyAPIManager.getInstance().vocabularies().findByName(vocabularyName).getId();

            String skosURI = hc.getString("/vocabulary/@url");
            String xsltPath = hc.getString("/vocabulary/@xslt");

            // if uri and xslt are configured
            if (StringUtils.isNotBlank(skosURI) && StringUtils.isNotBlank(xsltPath)) {

                // get data from uri
                String data = HttpUtils.getStringFromUrl(skosURI);

                // if data is found
                if (StringUtils.isNotBlank(data)) {
                    try {
                        StreamSource input = new StreamSource(new StringReader(data));
                        StreamSource xslt = new StreamSource(xsltPath);

                        TransformerFactory transformerFactory = TransformerFactory.newInstance();
                        Transformer transformer = transformerFactory.newTransformer(xslt);

                        ByteArrayOutputStream csvOutputStream = new ByteArrayOutputStream();
                        transformer.transform(input, new StreamResult(csvOutputStream));
                        ByteArrayInputStream csvInputStream = new ByteArrayInputStream(csvOutputStream.toByteArray());
                        VocabularyAPIManager.getInstance().vocabularies().cleanImportCsv(vocabularyId, csvInputStream);

                        initializeProperty(vocabularyName, labelField, valueField, property);
                    } catch (TransformerException e) {
                        String message = "Error while transforming vocabulary data from " + skosURI + " to CSV";
                        log.error(message, e);
                        Helper.setFehlerMeldung(message, e.getMessage());
                    } catch (APIException e) {
                        APIExceptionExtractor extractor = new APIExceptionExtractor(e);
                        String message = "Failed to update vocabulary \"" + vocabularyName + "\", Reason: \n"
                                + extractor.getLocalizedMessage(Helper.getSessionLocale());
                        log.error(message, e);
                        Helper.setFehlerMeldung(message, e.getMessage());
                    }
                }
            }
        } catch (APIException e) {
            APIExceptionExtractor extractor = new APIExceptionExtractor(e);
            String message =
                    "Failed to update vocabulary \"" + vocabularyName + "\", Reason: \n" + extractor.getLocalizedMessage(Helper.getSessionLocale());
            log.error(message, e);
            Helper.setFehlerMeldung(message, e.getMessage());
        }
    }

    public void duplicateField(ArcheProperty dp) {

        if (dp != null) {

            GoobiProperty property = new GoobiProperty(PropertyOwnerType.PROJECT);
            property.setOwner(selectedProject);
            property.setPropertyName(dp.getName());
            selectedProject.getProperties().add(property);

            ArcheProperty pp = new ArcheProperty();
            pp.setName(dp.getName());
            pp.setContainer("0");
            pp.setType(dp.getType());
            pp.setValidation(dp.getValidation());

            pp.setRepeatable(dp.isRepeatable());
            pp.setLanguageField(dp.isLanguageField());

            pp.setPattern(dp.getPattern());

            displayProperties.add(pp);
            pp.setProzesseigenschaft(property);
            pp.setValue(property.getPropertyValue());
            pp.setContainer(property.getContainer());

            pp.setPossibleValues(dp.getPossibleValues());

        }
    }

}
