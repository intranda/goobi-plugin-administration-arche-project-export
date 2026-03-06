package de.intranda.goobi.plugins;

import org.goobi.production.properties.DisplayProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArcheProperty extends DisplayProperty {

    private static final long serialVersionUID = -2369169504034341277L;

    private boolean repeatable;

    private boolean languageField;

    private String selectedLanguage;

}
