package org.rdf.validators.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

import org.apache.jena.rdf.model.Model;
import org.apache.tika.Tika;
import org.semarglproject.rdf.ParseException;
import org.semarglproject.rdf.TurtleSerializer;
import org.semarglproject.rdf.rdfa.RdfaParser;
import org.semarglproject.sink.CharOutputSink;
import org.semarglproject.source.StreamProcessor;
import org.topbraid.jenax.util.JenaUtil;
import nu.validator.htmlparser.common.XmlViolationPolicy;

public class RDFExtractor {
	
	public Model getDataModel(InputStream in, String rdfformat) throws IOException {
		Tika tika = new Tika();
    	String extension = "";
    	String mimetype = "";
        	
			InputStream inputStreamCloned = getCopy(in);
			in = inputStreamCloned; 
			if (rdfformat == null) {
				mimetype = tika.detect(inputStreamCloned);
				extension = MimeTypes.getExtensionFromMimeType(mimetype);
				System.out.println("set mimetype: " + mimetype);
				System.out.println("set extension: " + extension);
			} else {
				extension = rdfformat;
				System.out.println("set extension to rdf format: " + rdfformat);
			}
			 	
			Model dataModel = JenaUtil.createMemoryModel();
			
			System.out.println(extension);
			if(Objects.equals(extension, "html")) {
				
				OutputStream out = new ByteArrayOutputStream();
				CharOutputSink charOutputSink = new CharOutputSink("UTF-8");
				charOutputSink.connect(out);
				StreamProcessor streamProcessor = new StreamProcessor(RdfaParser.connect(TurtleSerializer.connect(charOutputSink)));
				nu.validator.htmlparser.sax.HtmlParser reader = new nu.validator.htmlparser.sax.HtmlParser(XmlViolationPolicy.ALTER_INFOSET);
		        streamProcessor.setProperty(StreamProcessor.XML_READER_PROPERTY, reader);		        
				try {
					streamProcessor.process(in, "http://example.com");
				} catch (ParseException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				//ValueFactory vf = SimpleValueFactory.getInstance();
				//IRI baseURI= vf.createIRI("http://shacl.validator.com/");
				
				//org.eclipse.rdf4j.model.Model rdf4jmodel = Rio.parse(dataStream, baseURI.toString(), RDFFormat.RDFA);
				
				//java.io.Writer writer = new StringWriter();
				//Rio.write(rdf4jmodel, writer, RDFFormat.TURTLE); 

				String html2trig = out.toString();
				//System.out.println(html2trig);
				InputStream is = new ByteArrayInputStream(html2trig.getBytes());
				dataModel.read(is, null, "Turtle");
		       
			} else if(Objects.equals(extension, "xhtml")) {
				System.out.println("parsing xhtml");
				OutputStream out = new ByteArrayOutputStream();
				CharOutputSink charOutputSink = new CharOutputSink("UTF-8");
				charOutputSink.connect(out);
				StreamProcessor streamProcessor = new StreamProcessor(RdfaParser.connect(TurtleSerializer.connect(charOutputSink)));
				try {
					streamProcessor.process(in, "http://example.com");
				} catch (Exception e) {
					nu.validator.htmlparser.sax.HtmlParser reader = new nu.validator.htmlparser.sax.HtmlParser(XmlViolationPolicy.ALTER_INFOSET);
			        streamProcessor.setProperty(StreamProcessor.XML_READER_PROPERTY, reader);
			        try {
						streamProcessor.process(in, "http://example.com");
					} catch (ParseException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
					}    
				}
				String html2trig = out.toString();
				//System.out.println(html2trig);
				InputStream is = new ByteArrayInputStream(html2trig.getBytes());
				dataModel.read(is, null, "Turtle");
			} else {
				dataModel.read(in, null, extension);
			}
			
			return dataModel;

	}
	
    public static InputStream getCopy(final InputStream inputStream) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int readLength = 0;
            while ((readLength = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, readLength);
            }
            outputStream.flush();
            return new ByteArrayInputStream(outputStream.toByteArray());
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
}
