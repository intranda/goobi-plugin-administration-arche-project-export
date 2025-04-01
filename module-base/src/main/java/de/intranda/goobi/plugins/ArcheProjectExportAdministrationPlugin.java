package de.intranda.goobi.plugins;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang3.StringUtils;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.GoobiProperty.PropertyOwnerType;
import org.goobi.beans.Project;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;
import org.goobi.production.properties.DisplayProperty;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.exceptions.DAOException;
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
            XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
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
}
