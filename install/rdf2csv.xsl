<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    xmlns:skos="http://www.w3.org/2004/02/skos/core#">

    <xsl:output method="text" encoding="UTF-8"/>

    <xsl:template match="/">
        <xsl:text>ID,Label,URI&#10;</xsl:text>

        <!-- Sortierte obere Descriptions -->
        <xsl:apply-templates select="//rdf:Description[skos:prefLabel and skos:exactMatch]">
            <xsl:sort 
                select="(skos:prefLabel[@xml:lang='de'] 
                        | skos:prefLabel[@xml:lang='en']
                        | skos:prefLabel)[1]"
                data-type="text"
                order="ascending"/>
        </xsl:apply-templates>
    </xsl:template>


    <xsl:template match="rdf:Description">

        <!-- Gewünschtes PrefLabel -->
        <xsl:variable name="pref">
            <xsl:choose>
                <xsl:when test="skos:prefLabel[@xml:lang='de']">
                    <xsl:value-of select="skos:prefLabel[@xml:lang='de'][1]"/>
                </xsl:when>
                <xsl:when test="skos:prefLabel[@xml:lang='en']">
                    <xsl:value-of select="skos:prefLabel[@xml:lang='en'][1]"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:value-of select="skos:prefLabel[1]"/>
                </xsl:otherwise>
            </xsl:choose>
        </xsl:variable>

        <!-- exactMatch der oberen Description -->
        <xsl:variable name="upperMatch" select="skos:exactMatch/@rdf:resource"/>

        <!-- passende untere Description suchen -->
        <xsl:variable name="lower"
            select="//rdf:Description[@rdf:about = $upperMatch]"/>

        <!-- Das exactMatch der unteren Description -->
        <xsl:variable name="lowerMatch"
            select="$lower/skos:exactMatch/@rdf:resource"/>

        <!-- Nur Ausgabe wenn beide existieren -->
        <xsl:if test="string($pref) and string($lowerMatch)">
            <xsl:text>,</xsl:text>
            <xsl:value-of select="$pref"/>
            <xsl:text>,</xsl:text>
            <xsl:value-of select="$lowerMatch"/>
            <xsl:text>&#10;</xsl:text>
        </xsl:if>

    </xsl:template>

</xsl:stylesheet>
