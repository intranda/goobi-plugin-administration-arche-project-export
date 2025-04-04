package de.intranda.goobi.plugins;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import jakarta.faces.model.SelectItem;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class ArcheProjectExportAdministrationPlugin implements IAdministrationPlugin {

    private static final long serialVersionUID = 8616559554562036309L;

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

    private XMLConfiguration config;

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
                    pp.getPossibleValues().add(new SelectItem(selectItem.getString("@label"), selectItem.getString("@value")));
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

        Model model = createTopCollectionDocument();

        // store ttl in destination

        try (OutputStream out = new FileOutputStream("/tmp/" + selectedProject.getTitel() + ".ttl")) {
            RDFDataMgr.write(out, model, RDFFormat.TURTLE_PRETTY);
        } catch (IOException e) {
            log.error(e);
        }

    }

    private Model createTopCollectionDocument() {
        String languageCode = "en";
        Model model = ModelFactory.createDefaultModel();
        // collection name
        String topCollectionIdentifier = IDENTIFIER_PREFIX + selectedProject.getTitel();

        model.setNsPrefix("api", "https://arche.acdh.oeaw.ac.at/api/");
        model.setNsPrefix("acdh", "https://vocabs.acdh.oeaw.ac.at/schema#");

        Resource projectResource =
                model.createResource(topCollectionIdentifier, model.createResource(model.getNsPrefixURI("acdh") + "TopCollection"));
        //        hasTitle -> project name
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasTitle"), selectedProject.getTitel(), languageCode);
        //        hasIdentifier -> topCollectionIdentifier
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasIdentifier"),
                model.createResource(topCollectionIdentifier));
        //        hasPid  -> leave it free

        //        hasUrl -> viewer root url https://viewer.acdh.oeaw.ac.at/viewer
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUrl"), "https://viewer.acdh.oeaw.ac.at/viewer",
                XSDDatatype.XSDanyURI);
        //        hasDescription -> project description (property, config, ...)

        String propertyName = config.getString("/property[@ttlField='hasDescription']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDescription"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasLifeCycleStatus -> project active: set to https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active Otherwise, https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed
        if (selectedProject.getProjectIsArchived().booleanValue()) {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/active"));
        } else {
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLifeCycleStatus"),
                    model.createResource("https://vocabs.acdh.oeaw.ac.at/archelifecyclestatus/completed"));
        }
        //        hasUsedSoftware -> Goobi
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasUsedSoftware"), "Goobi");

        //        hasContact -> config
        propertyName = config.getString("/property[@ttlField='hasContact']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContact"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasContributor -> config
        propertyName = config.getString("/property[@ttlField='hasContributor']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasContributor"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasDigitisingAgent -> config
        propertyName = config.getString("/property[@ttlField='hasDigitisingAgent']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDigitisingAgent"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasMetadataCreator -> config
        propertyName = config.getString("/property[@ttlField='hasMetadataCreator']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasMetadataCreator"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasRelatedDiscipline -> config, value from https://vocabs.acdh.oeaw.ac.at/oefosdisciplines/
        propertyName = config.getString("/property[@ttlField='hasRelatedDiscipline']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasRelatedDiscipline"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasSubject -> config
        propertyName = config.getString("/property[@ttlField='hasSubject']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasSubject"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        String query =
                "select min(value), max(value) from metadata where name = 'PublicationYear' and processid in (select ProzesseId from prozesse where ProjekteID = (Select projekteid from projekte where titel='"
                        + selectedProject.getTitel() + "')) group by name;";
        @SuppressWarnings("unchecked")
        List<Object> results = ProcessManager.runSQL(query);
        if (!results.isEmpty()) {
            Object[] row = (Object[]) results.get(0);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageStartDate"), String.valueOf(row[0]),
                    XSDDatatype.XSDdate);
            projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCoverageEndDate"), String.valueOf(row[1]),
                    XSDDatatype.XSDdate);
        }

        //        hasOwner -> config
        propertyName = config.getString("/property[@ttlField='hasOwner']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasOwner"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasRightsHolder -> Project -> mets rights holder
        propertyName = config.getString("/property[@ttlField='hasRightsHolder']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasRightsHolder"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        //        hasLicensor -> config
        propertyName = config.getString("/property[@ttlField='hasLicensor']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLicensor"),
                        gp.getPropertyValue(), languageCode);
            }
        }
        //        hasLicense -> TODO property
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasLicense"),
                model.createResource("https://vocabs.acdh.oeaw.ac.at/archelicenses/publicdomain-1-0"));
        //        hasCreatedStartDate -> TODO project start date as YYYY-MM-DD
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedStartDate"), "2020-01-01", XSDDatatype.XSDdate);
        //        hasCreatedEndDate -> TODO project end date as YYYY-MM-DD
        projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCreatedEndDate"), "2023-12-31", XSDDatatype.XSDdate);

        //        hasDepositor -> config
        propertyName = config.getString("/property[@ttlField='hasDepositor']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasDepositor"),
                        gp.getPropertyValue(), languageCode);
            }
        }
        //        hasCurator -> config
        propertyName = config.getString("/property[@ttlField='hasCurator']/@name");
        for (GoobiProperty gp : selectedProject.getProperties()) {
            if (gp.getPropertyName().equals(propertyName)) {
                projectResource.addProperty(model.createProperty(model.getNsPrefixURI("acdh"), "hasCurator"),
                        gp.getPropertyValue(), languageCode);
            }
        }

        return model;
    }

}
