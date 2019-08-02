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
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
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
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.util.StandardValidators;
import org.rdf.validators.utils.RDFExtractor;
import org.topbraid.shacl.util.ModelPrinter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"rules", "validation", "rdf", "reasoner",})
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({
    @WritesAttribute(attribute = "validaterules.invalid.error", description = "If the flow file is routed to the invalid relationship "
            + "the attribute will contain the error message resulting from the validation failure.")
})
@CapabilityDescription("Validates the contents of FlowFiles once the business rules enriched them.")
public class GenericRuleValidatorProcessor extends AbstractProcessor {

	public static final String ERROR_ATTRIBUTE_KEY = "validaterules.invalid.error";
	public static final String RDF_FORMAT = "rdf.format";
    
    public static final PropertyDescriptor MODEL_FILE = new PropertyDescriptor
            .Builder().name("MODEL File")
            .displayName("MODEL File")
            .description("The path to the MODEL file that is to be used for validation")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();
    
    public static final PropertyDescriptor REASONER_RULE_FILE = new PropertyDescriptor
            .Builder().name("REASONER RULE File")
            .displayName("REASONER RULE File")
            .description("The path to the reasoner rule file that is to be used for validation")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .build();

    public static final PropertyDescriptor VALIDITY_LEVEL = new PropertyDescriptor
            .Builder().name("Validity level")
            .displayName("Validity level")
            .description("Choose -valid- or -clean- (stronger)")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .defaultValue("valid").allowableValues("valid", "clean")
            .build();
    
    public static final PropertyDescriptor OUTPUT_FORMAT = new PropertyDescriptor
            .Builder().name("Output format")
            .displayName("Output format")
            .description("Choose TURTLE, TTL, RDF/XML, RDF/XML-ABBREV, N-TRIPLE, N3")
            .required(true)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .defaultValue("TURTLE").allowableValues("TURTLE", "TTL", "RDF/XML", "RDF/XML-ABBREV","N-TRIPLE","N3")
            .build();

    public static final Relationship REL_VALID  = new Relationship.Builder()
            .name("valid")
            .description("FlowFiles that are successfully validated after the validation are routed to this relationship")
            .build();
    
    public static final Relationship REL_INVALID = new Relationship.Builder()
            .name("invalid")
            .description("FlowFiles that are not valid after the validation are routed to this relationship")
            .build();
    
    
    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private final AtomicReference<File> modelRef = new AtomicReference<>();
    private final AtomicReference<File> ruleRef = new AtomicReference<>();
    private final AtomicReference<String> outputRef = new AtomicReference<>();
    private final AtomicReference<String> validityRef = new AtomicReference<>();
    private final AtomicReference<String> ttlResult = new AtomicReference<>();
    private final AtomicReference<ValidityReport> validity_report = new AtomicReference<>();
    private final AtomicReference<String> inference_model = new AtomicReference<>();
    
    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(MODEL_FILE);
        descriptors.add(REASONER_RULE_FILE);
        descriptors.add(VALIDITY_LEVEL);
        descriptors.add(OUTPUT_FORMAT);
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

            final File model_file = new File(context.getProperty(MODEL_FILE).evaluateAttributeExpressions().getValue());
            // Ensure the file exists
            if (!model_file.exists()) {
                throw new FileNotFoundException("model file not found at specified location: " + model_file.getAbsolutePath());
            }
            this.modelRef.set(model_file);
            
            final File reasoner_rule_file = new File(context.getProperty(REASONER_RULE_FILE).evaluateAttributeExpressions().getValue());
            // Ensure the file exists
            if (!reasoner_rule_file.exists()) {
                throw new FileNotFoundException("reasoner rule file not found at specified location: " + reasoner_rule_file.getAbsolutePath());
            }
            this.ruleRef.set(reasoner_rule_file);
            
            final String validity_level = context.getProperty(VALIDITY_LEVEL).evaluateAttributeExpressions().getValue();
            this.validityRef.set(validity_level);
            
            final String output_format = context.getProperty(OUTPUT_FORMAT).evaluateAttributeExpressions().getValue();
            this.outputRef.set(output_format);
            
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
    	final List<FlowFile> flowFiles= session.get(50);
    	if (flowFiles.isEmpty()) {
            return;
        }
    	
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
            final AtomicBoolean validrules = new AtomicBoolean(true);
            final String rdfformat = flowFile.getAttribute(RDF_FORMAT);
         
            session.read(flowFile, new InputStreamCallback() {
                @Override
                public void process(InputStream in) throws IOException {
                	
                	Model dataModel = new RDFExtractor().getDataModel(in,rdfformat);
                	
    		        dataModel.add(vocModel);
    		                				
					final File rules = ruleRef.get();
			    	InputStream ruleStream = null;
					try {
						ruleStream = new FileInputStream(rules);
					} catch (FileNotFoundException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					//String rulez = IOUtils.toString(ruleStream, StandardCharsets.UTF_8 );
					//System.out.println("rulez:"+rulez);
					Reader br = FileUtils.asUTF8(ruleStream);
					BufferedReader br2 = new BufferedReader(br);
					GenericRuleReasoner reasoner = new GenericRuleReasoner( Rule.parseRules(Rule.rulesParserFromReader(br2)) );
					reasoner.setDerivationLogging(true);
					reasoner.setOWLTranslation(true);
					reasoner.setTraceOn(true);
					reasoner.setTransitiveClosureCaching(true);
					InfModel infmodel = ModelFactory.createInfModel( reasoner, dataModel );
					
    				String syntax = outputRef.get();
    				StringWriter out = new StringWriter();
    				infmodel.write(out, syntax);
    				inference_model.set(out.toString());
    				
					validity_report.set(infmodel.validate());
					String arrayreportsrules ="";
					for (Iterator i = validity_report.get().getReports(); i.hasNext(); ) {
						arrayreportsrules = arrayreportsrules.concat(i.next().toString());
				    }
					ttlResult.set(arrayreportsrules.toString());
					System.out.println(ModelPrinter.get().print(infmodel));
    				if(validityRef.get().equals(new String("valid"))) {				
    					if(validity_report.get().isValid()) {
    						System.out.println("validation rules is ok");
    						validrules.set(true); 
    					} else {
    						System.out.println("validation rules is not ok");
    						validrules.set(false);
    					}
    				} else {
    					if(validity_report.get().isClean()) {
    						System.out.println("validation clean rules is ok");
    						validrules.set(true);
    					} else {
    						System.out.println("validation clean rules is not ok");
    						validrules.set(false);
    					}
    				}
                }
            });
        	
            flowFile = session.write(flowFile, new OutputStreamCallback() {
                @Override
                public void process(OutputStream out) throws IOException {
                	
                	System.out.println("Writing generic rules flow file");
                	out.write(inference_model.get().getBytes());
                }
            });
        	
            if (validrules.get()) {
            	flowFile = session.putAttribute(flowFile, RDF_FORMAT, outputRef.get());
                logger.debug("Successfully validated {} against Generic Rule validator processor; routing to 'valid'", new Object[]{flowFile});
                session.getProvenanceReporter().route(flowFile, REL_VALID);
                session.transfer(flowFile, REL_VALID);
            } else {
            	flowFile = session.putAttribute(flowFile, ERROR_ATTRIBUTE_KEY, ttlResult.get());
                logger.info("Failed to validate {} against schema due to {}; routing to 'invalid'", new Object[]{flowFile, ttlResult.get()});	
                session.getProvenanceReporter().route(flowFile, REL_INVALID);
                session.transfer(flowFile, REL_INVALID);
            }
        }
        // TODO implement
    }


}

