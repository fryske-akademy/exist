xquery version "1.0";

declare option exist:serialize "media-type=text/xml omit-xml-declaration=yes";

<select id="saved" name="saved">
    {
    for $entry in //example-queries/query
    return
        <option value="{$entry/code}">{$entry/description}</option>
    }
</select>