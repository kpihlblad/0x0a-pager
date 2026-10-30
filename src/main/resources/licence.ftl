<#function licenseFormat licenses>
    <#assign result = ""/>
    <#list licenses as license>
        <#assign result = result + " (" + license + ")"/>
    </#list>
    <#return result>
</#function>

<#function artifactFormat p> <#-- Construct the name + version string (columns 0-39, width 40) -->
    <#if p.name?? && p.name?index_of("Unnamed") gt -1>
        <#assign nameVersion = (p.artifactId + " " + p.version)?right_pad(60)>
    <#else>
        <#assign nameVersion = ((p.name!"Unnamed") + " " + p.version)?right_pad(60)>
    </#if>
<#-- Construct the URL string (columns 40-69, width 30) -->
    <#assign url = (p.url!"-")?right_pad(76)>
<#-- Return the combined string for name+version and URL -->
    <#return nameVersion + url>
</#function>

<#if dependencyMap?size == 0>
    The project has no dependencies.
<#else>
    <#list dependencyMap as e>
        <#assign project = e.getKey()/>
        <#assign licenses = e.getValue()/>
${artifactFormat(project)}${licenseFormat(licenses)}
    </#list>
</#if>