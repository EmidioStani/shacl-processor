/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rdf.validators;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.resultset.ResultsFormat;
import org.apache.jena.util.FileUtils;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.rdf.validators.utils.RDFExtractor;
import org.rdf.validators.utils.ValidationResult;
import org.topbraid.shacl.util.ModelPrinter;
import org.topbraid.shacl.validation.ValidationUtil;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"shacl", "validation", "rdf"})
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({
    @WritesAttribute(attribute = "validateshacl.invalid.error", description = "If the flow file is routed to the invalid relationship "
            + "the attribute will contain the error message resulting from the validation failure.")
})
@CapabilityDescription("Validates the contents of FlowFiles against a user-specified SHACL file")
public class SHACLValidatorProcessor extends AbstractProcessor {

	public static final String ERROR_ATTRIBUTE_KEY = "validateshacl.invalid.error";
	
    public static final PropertyDescriptor SHACL_FILE = new PropertyDescriptor
            .Builder().name("SHACL File")
            .displayName("SHACL File")
            .description("The path to the SHACL file that is to be used for validation")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor MODEL_FILE = new PropertyDescriptor
            .Builder().name("MODEL File")
            .displayName("MODEL File")
            .description("The path to the MODEL file that is to be used for validation")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor QUERY_FILE = new PropertyDescriptor
            .Builder().name("QUERY File")
            .displayName("QUERY File")
            .description("The path to the QUERY file that is to be used for validation")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor OUTPUT_QUERY_FORMAT = new PropertyDescriptor
            .Builder().name("Output query format")
            .displayName("Output query format")
            .description("Choose xml, json, thrift, sse, csv, tsv, text, count, tuples, node, rdf, n3, ttl, nt, ntriples, nq, trig")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .defaultValue("ttl").allowableValues("xml", "json", "thrift", "sse", "csv", "tsv", "text", "count", "tuples", "node", "rdf", "n3", "ttl", "nt", "nq", "trig")
            .build();

    public static final Relationship REL_VALID  = new Relationship.Builder()
            .name("valid")
            .description("FlowFiles that are successfully validated against the schema are routed to this relationship")
            .build();
    
    public static final Relationship REL_INVALID = new Relationship.Builder()
            .name("invalid")
            .description("FlowFiles that are not valid according to the specified schema are routed to this relationship")
            .build();
    
    
    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private final AtomicReference<File> schemaRef = new AtomicReference<>();
    private final AtomicReference<File> modelRef = new AtomicReference<>();
    private final AtomicReference<File> queryRef = new AtomicReference<>();
    private final AtomicReference<String> outputRef = new AtomicReference<>();
    private final AtomicReference<String> ttlResult = new AtomicReference<>();
    
    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(SHACL_FILE);
        descriptors.add(MODEL_FILE);
        descriptors.add(QUERY_FILE);
        descriptors.add(OUTPUT_QUERY_FORMAT);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(REL_VALID);
        relationships.add(REL_INVALID);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @OnScheduled
    public void parseSchema(final ProcessContext context) throws IOException {
            final File shacl_file = new File(context.getProperty(SHACL_FILE).evaluateAttributeExpressions().getValue());
            // Ensure the file exists
            if (!shacl_file.exists()) {
                throw new FileNotFoundException("SHACL file not found at specified location: " + shacl_file.getAbsolutePath());
            }
            this.schemaRef.set(shacl_file);
            final File model_file = new File(context.getProperty(MODEL_FILE).evaluateAttributeExpressions().getValue());
            // Ensure the file exists
            if (!model_file.exists()) {
                throw new FileNotFoundException("model file not found at specified location: " + model_file.getAbsolutePath());
            }
            this.modelRef.set(model_file);
            final File query_file = new File(context.getProperty(QUERY_FILE).evaluateAttributeExpressions().getValue());
            // Ensure the file exists
            if (!query_file.exists()) {
                throw new FileNotFoundException("query file not found at specified location: " + query_file.getAbsolutePath());
            }
            this.queryRef.set(query_file);
            
            final String output_format = context.getProperty(OUTPUT_QUERY_FORMAT).evaluateAttributeExpressions().getValue();
            this.outputRef.set(output_format);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
    	final List<FlowFile> flowFiles= session.get(50);
    	if (flowFiles.isEmpty()) {
            return;
        }
    	final File schema = schemaRef.get();
    	InputStream schemaStream =null;
		try {
			schemaStream = new FileInputStream(schema);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	Model shaclModel = ModelFactory.createDefaultModel() ;
    	shaclModel.read(schemaStream, "http://shacl.example.com", "TURTLE");
    	
    	final File model = modelRef.get();
    	InputStream modelStream = null;
		try {
			modelStream = new FileInputStream(model);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	Model vocModel = ModelFactory.createDefaultModel() ;
        vocModel.read(modelStream, "http://model.example.com" , "TURTLE");
		
        final ComponentLog logger = getLogger();
        for (FlowFile flowFile : flowFiles) {
            final AtomicBoolean valid = new AtomicBoolean(true);
            final String rdfformat = null;
            
            session.read(flowFile, new InputStreamCallback() {
                @Override
                public void process(InputStream in) throws IOException {
                	Model dataModel = new RDFExtractor().getDataModel(in, rdfformat);
                	
    		        dataModel.add(vocModel);
        		    shaclModel.add(vocModel);
        		        
    		        Resource resource = ValidationUtil.validateModel(dataModel, shaclModel, true);
    				Model reportModel = resource.getModel();
    				reportModel.setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
    				
    				String queryStr = "prefix sh: <http://www.w3.org/ns/shacl#> "
		    						+ "SELECT ?report"
		    						+ "WHERE { ?report a sh:ValidationReport ."
		    						+ "?report sh:conforms true }";
    				Query query = QueryFactory.create(queryStr);
    				QueryExecution qe = QueryExecutionFactory.create(query, reportModel);
    				Boolean isModelConform = qe.execSelect().hasNext();
    				
    				List<ValidationResult> validationResultsList = formatOutput(reportModel,queryRef.get());
    				String resultQuery = getQueryResult(reportModel, queryRef.get(), outputRef.get());
    				//ttlResult.set(ModelPrinter.get().print(reportModel));
    				ttlResult.set(resultQuery);
    				System.out.println(ttlResult.get());
    				if (isModelConform) {
    					System.out.println("validation result list is empty");
    					valid.set(true);
    				} else {
    					System.out.println("validation shacl is not ok");
    					valid.set(false);
    				}
                }
            });

            if ( valid.get() ) {
                logger.error("Successfully validated {} against schema; routing to 'valid'", new Object[]{flowFile});
                session.getProvenanceReporter().route(flowFile, REL_VALID);
                session.transfer(flowFile, REL_VALID);
            } else {
            	flowFile = session.putAttribute(flowFile, ERROR_ATTRIBUTE_KEY, ttlResult.get());
                   logger.error("Failed to validate {} against schema due to {}; routing to 'invalid'", new Object[]{flowFile, ttlResult.get()});	
                session.getProvenanceReporter().route(flowFile, REL_INVALID);
                session.transfer(flowFile, REL_INVALID);
            }
        }
    }
    
	private String getQueryResult(Model model, File queryfile, String rdfformat) throws IOException {
		
		InputStream is = null;
		try {
			is = new FileInputStream(queryfile);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		String queryStr = FileUtils.readWholeFileAsUTF8(is);
		
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		Query query = QueryFactory.create(queryStr);
		QueryExecution qe = QueryExecutionFactory.create(query, model);
		ResultSet queryResult = qe.execSelect();
	
		ResultsFormat format = ResultsFormat.lookup(rdfformat);
		if (format != null) {
			ResultSetFormatter.output(outputStream, queryResult, format);
		} else {
			// output txt
			String resultOutput = ResultSetFormatter.asText(queryResult);
			if (resultOutput != null) {
				outputStream.write(resultOutput.getBytes());
			}
		}

		qe.close();
		
		String output = outputStream.toString();
		outputStream.close();
	
    	return output;
    	
	}
    
	/**
	 * Method to create a list of validation results from the validationModel
	 * 
	 * @param model
	 *            The ValidationModel which should be converted
	 * @throws IOException
	 *             Writing to stream might cause problems
	 */
	private static List<ValidationResult> formatOutput(Model model, File queryfile) throws IOException {
		// formating result using query to CSV
		InputStream is = null;
		try {
			is = new FileInputStream(queryfile);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		String queryStr = FileUtils.readWholeFileAsUTF8(is);
		ByteArrayOutputStream resultStream = formatOutput(model, queryStr, "CSV");
		String output = resultStream.toString();
		resultStream.close();
		// Split the CSV string on newline character, add to ArrayList and remove empty lines
    	List<String> linesList = Arrays.asList(output.split("[\\n\\r]"));
    	ArrayList<String> lines = new ArrayList<String>();
    	lines.addAll(linesList);
    	lines.removeAll(Arrays.asList(""));
    	
    	// Replace empty values with NA. 
    	// If last value is empty, this cannot be changed. It is handled later
    	List<List<String>> items = new ArrayList<>();
    	for (int i = 0; i < lines.size(); i++) {
    		String templine = lines.get(i);
    		while (templine.contains(",,")) {
    			System.out.println("templine index:" + i);
    			System.out.println("templine before:" + templine);
    			templine = templine.replaceAll(",,", ",NA,");
    			System.out.println("templine after:" + templine);
    		}
    		lines.set(i, templine);
    	}
    	
    	// Split each line on "," and if the last value was empty, complete with NA
    	List<String> firstLineSplit = new ArrayList<>(Arrays.asList((lines.get(0).split("\\s*,\\s*"))));
    	int size = firstLineSplit.size();
    	for (int i = 0; i < lines.size(); i++) {
    		List<String> splittedLine = new ArrayList<>(Arrays.asList((lines.get(i).split("\\s*,\\s*"))));
    		if (splittedLine.size() != size ) {
    			splittedLine.add("NA");
    		}
    		items.add(splittedLine);
    	}
    	
    	// Load into a List of ValidationResults
    	List<ValidationResult> validationList = new ArrayList<ValidationResult>();
    	//Start at 1 because the first row contains the headers and we do not want to include those.
    	for (int j = 1; j < items.size(); j++) {
    		ValidationResult validationResult = new ValidationResult();
    		List<String> tempitem = items.get(j);
    		validationResult.setFocusNode(tempitem.get(0));
    		validationResult.setResultMessage(tempitem.get(1));
    		validationResult.setResultPath(tempitem.get(2));
    		validationResult.setResultSeverity(tempitem.get(3));
    		validationResult.setValue(tempitem.get(4));
    		validationResult.setSourceConstraint(tempitem.get(5));
    		validationResult.setSourceConstraintComponent(tempitem.get(6));
    		validationResult.setSourceShape(tempitem.get(7));

    		
    		validationList.add(validationResult);
    		
    	}
		
		return validationList;
	}
	
	/**
	 * Method filters the given result using sparql and provides byte array stream containing the result of
	 * the sparql query using the provided format. The different formats are created using standard jena
	 * ResultSetFormatter
	 * 
	 * @param result
	 *            Result from shacl validation
	 * @param queryStr
	 *            String containing sparql query
	 * @param format
	 *            String switching output format, currently only provided TXT, CSV,
	 *            XML
	 * @return ByteArrayOutputStream
	 * @throws IOException
	 *             Writing to stream might cause problems
	 */
	private static ByteArrayOutputStream formatOutput(Model result, String queryStr, String format) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		Query query = QueryFactory.create(queryStr);

		QueryExecution qe = QueryExecutionFactory.create(query, result);

		ResultSet queryResult = qe.execSelect();

		queryResult.getResourceModel().setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
		if (format.matches("(CSV)")) {
			ResultSetFormatter.output(outputStream, queryResult, ResultsFormat.FMT_RS_CSV);
		} else if (format.matches("(XML)")) {
			ResultSetFormatter.outputAsXML(outputStream, queryResult);
		} else if (format.matches("(TTL)")) {
			ResultSetFormatter.output(outputStream, queryResult, ResultsFormat.FMT_RDF_TTL);
		} else {
			// output txt
			String resultOutput = ResultSetFormatter.asText(queryResult);
			if (resultOutput != null) {
				outputStream.write(resultOutput.getBytes());
			}
		}
		// close this resource
		qe.close();

		return outputStream;

	}
}
