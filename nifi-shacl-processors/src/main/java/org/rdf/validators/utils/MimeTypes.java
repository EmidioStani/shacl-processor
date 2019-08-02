package org.rdf.validators.utils;

import java.util.HashMap;

public class MimeTypes {
	static HashMap<String, String> TheHashMap = new HashMap<String, String>();

    public MimeTypes() {
        super();
    }

    static {
        CreateHashMap();
    }
    
    private static void CreateHashMap() {

        // This list is not complete... 
        // I got it from https://www.freeformatter.com/mime-types-list.html
        TheHashMap.put("application/xhtml+xml", "xhtml");
        TheHashMap.put("text/html", "html");
        TheHashMap.put("application/rdf+xml", "rdf/xml");
    }
    
    public static String getExtensionFromMimeType(String TheMimeType) {

        String TheExtension = "Extension Not Found";
        for(String key: TheHashMap.keySet()){
            if(key.toLowerCase().equals(TheMimeType.toLowerCase())) {
                TheExtension = TheHashMap.get(key);
                return TheExtension;
            }
        }

        return TheExtension;
    }
}

