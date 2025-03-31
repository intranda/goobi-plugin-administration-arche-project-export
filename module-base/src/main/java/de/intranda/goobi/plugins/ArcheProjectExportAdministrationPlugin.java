package de.intranda.goobi.plugins;

import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;

import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class ArcheProjectExportAdministrationPlugin implements IAdministrationPlugin {

    @Getter
    private String title = "intranda_administration_arche_project_export";

    @Getter
    private String value;

    @Override
    public PluginType getType() {
        return PluginType.Administration;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_administration_arche_project_export.xhtml";
    }

    /**
     * Constructor
     */
    public ArcheProjectExportAdministrationPlugin() {
        log.info("ArcheProjectExport admnistration plugin started");
        value = ConfigPlugins.getPluginConfig(title).getString("value", "default value");
    }   
}
