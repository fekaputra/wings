package edu.isi.wings.portal.controllers;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import edu.isi.wings.catalog.component.ComponentFactory;
import edu.isi.wings.catalog.component.api.ComponentReasoningAPI;
import edu.isi.wings.catalog.data.DataFactory;
import edu.isi.wings.catalog.data.api.DataReasoningAPI;
import edu.isi.wings.common.UuidGen;
import edu.isi.wings.common.kb.PropertiesHelper;
import edu.isi.wings.execution.engine.classes.RuntimePlan;
import edu.isi.wings.ontapi.KBTriple;
import edu.isi.wings.planner.api.WorkflowGenerationAPI;
import edu.isi.wings.planner.api.impl.kb.WorkflowGenerationKB;
import edu.isi.wings.portal.classes.Config;
import edu.isi.wings.portal.classes.JsonHandler;
import edu.isi.wings.workflow.plan.api.ExecutionPlan;
import edu.isi.wings.workflow.template.TemplateFactory;
import edu.isi.wings.workflow.template.api.Template;
import edu.isi.wings.workflow.template.api.TemplateCreationAPI;
import edu.isi.wings.workflow.template.api.impl.kb.TemplateKB;
import edu.isi.wings.workflow.template.classes.Link;
import edu.isi.wings.workflow.template.classes.Node;
import edu.isi.wings.workflow.template.classes.sets.Binding;
import edu.isi.wings.workflow.template.classes.sets.SetExpression;
import edu.isi.wings.workflow.template.classes.sets.ValueBinding;
import edu.isi.wings.workflow.template.classes.sets.WingsSet;
import edu.isi.wings.workflow.template.classes.variables.ComponentVariable;
import edu.isi.wings.workflow.template.classes.variables.Variable;

@SuppressWarnings("unused")
public class PlanController {
	private DataReasoningAPI dc;
	private ComponentReasoningAPI cc;
	private TemplateCreationAPI tc;
	private WorkflowGenerationAPI wg;

	private Config config;
	private PrintWriter out;
	
	private Gson json;
	private Properties props;

	private String wliburl;
	private String dcdomns;
	private String dclibns;
	private String pcdomns;
	private String wflowns;
	
	public PlanController(Config config, PrintWriter out) {
		this.config = config;
		this.out = out;
		this.json = JsonHandler.createTemplateGson();
		this.props = config.getProperties();

		tc = TemplateFactory.getCreationAPI(props);
		cc = ComponentFactory.getReasoningAPI(props);
		dc = DataFactory.getReasoningAPI(props);
		wg = new WorkflowGenerationKB(props, dc, cc, UuidGen.generateAUuid(""));

		this.wliburl = (String) props.get("domain.workflows.dir.url");
		this.dcdomns = (String) props.get("ont.domain.data.url") + "#";
		this.dclibns = (String) props.get("lib.domain.data.url") + "#";
		this.pcdomns = (String) props.get("ont.domain.component.ns");
		this.wflowns = (String) props.get("ont.workflow.url") + "#";
	}
	
	@SuppressWarnings("rawtypes")
	public void printSuggestedDataJSON(String tplid, Map keyvals) {
		printPlannerJSON(tplid, keyvals, "getData");
	}

	@SuppressWarnings("rawtypes")
	public void printSuggestedParametersJSON(String tplid, Map keyvals) {
		printPlannerJSON(tplid, keyvals, "getParameters");
	} 
	
	@SuppressWarnings("rawtypes")
	public void printExpandedTemplatesJSON(String tplid, Map keyvals) {
		printPlannerJSON(tplid, keyvals, "getExpansions");
	}

	public void printElaboratedTemplateJSON(String tplid, String templatejson, String consjson) {
		Template tpl = JsonHandler.getTemplateFromJSON(this.json, templatejson, consjson);
		tpl = wg.getInferredTemplate(tpl);
		tpl.setID(tplid);
		
		HashMap<String, Object> extra = new HashMap<String, Object>();
		extra.put("explanations", wg.getExplanations());
		this.out.println(JsonHandler.getTemplateJSON(json, tpl, extra));
	}
	
	@SuppressWarnings("rawtypes")
	private void printPlannerJSON(String tplid, Map keyvals, String op) {
		Template tpl = tc.getTemplate(tplid);
		this.addTemplateBindings(tpl, keyvals);
		
		Template itpl = wg.getInferredTemplate(tpl);
		ArrayList<Template> candidates = wg.specializeTemplates(itpl);
		if(candidates.size() == 0) {
			printError();
			return;
		}
		
		ArrayList<Template> bts = new ArrayList<Template>();
		for(Template t : candidates)
			bts.addAll(wg.selectInputDataObjects(t));
		if(bts.size() == 0) {
			printError();
			return;
		}
		if(op.equals("getData")) {
			printDataBindingsJSON(bts);
			return;
		}
		
		wg.setDataMetricsForInputDataObjects(bts);

		ArrayList<Template> cts = new ArrayList<Template>();
		for(Template bt : bts)
			cts.addAll(wg.configureTemplates(bt));
		if(cts.size() == 0) {
			printError();
			return;
		}
		if(op.equals("getParameters")) {
			printParameterBindingsJSON(cts);
			return;
		}

		ArrayList<Template> ets = new ArrayList<Template>();
		for(Template ct : cts)
			ets.add(wg.getExpandedTemplate(ct));
		if(ets.size() == 0) {
			printError();
			return;
		}
		if(op.equals("getExpansions")) {
			printTemplatesJSON(ets);
			return;
		}
		
		printError();
	}
		
	private void printTemplatesJSON(ArrayList<Template> ts) {
		ArrayList<Object> template_stores = new ArrayList<Object>();
		for(Template t : ts) {
			ArrayList<String> varids = new ArrayList<String>();
			for(Variable v : t.getVariables()) varids.add(v.getID());
			HashMap<String, Object> tstore = new HashMap<String, Object>();
			tstore.put("template",  t);
			tstore.put("constraints",  t.getConstraintEngine().getConstraints(varids));
			template_stores.add(tstore);
		}
		HashMap<String, Object> map = new HashMap<String, Object>(); 
		map.put("explanations", wg.getExplanations());
		map.put("error",  false);
		map.put("templates", template_stores);
		map.put("output",  "");
		this.printEncodedResults(map); 
	}
	
	private void printDataBindingsJSON(ArrayList<Template> bts) {
		HashMap<String, Object> map = new HashMap<String, Object>(); 
		map.put("explanations", wg.getExplanations());
		map.put("error",  false);
		map.put("bindings", getDataBindings(bts));
		map.put("output",  "");
		this.printEncodedResults(map); 
	}
	
	private void printParameterBindingsJSON(ArrayList<Template> cts) {
		HashMap<String, Object> map = new HashMap<String, Object>(); 
		map.put("explanations", wg.getExplanations());
		map.put("error",  false);
		map.put("bindings", getParameterBindings(cts));
		map.put("output",  "");
		this.printEncodedResults(map); 
	}
	
	private void printError(Template tpl) {
		tpl.delete();
		printError();
	}
	
	private void printError() {
		HashMap<String, Object> map = new HashMap<String, Object>(); 
		map.put("explanations", wg.getExplanations());
		map.put("error",  true);
		map.put("bindings", "{}");
		this.printEncodedResults(map);
	}
	
	private void printEncodedResults(HashMap<String, Object> map) {
		Boolean error = (Boolean) map.get("error");
		HashMap<String, Object> results = new HashMap<String, Object>();
		results.put("success", (Boolean)!error);
		results.put("data", map);
		json.toJson(results, this.out);
	}
	
	private ArrayList<TreeMap<String, Binding>> getDataBindings(ArrayList<Template> bts) {
		ArrayList<TreeMap<String, Binding>> blist = new ArrayList<TreeMap<String, Binding>>();
		HashMap<String, Boolean> bindingstrs = new HashMap<String, Boolean>(); 
		for(Template bt: bts) {
			TreeMap<String, Binding> bindings = new TreeMap<String, Binding>();
			for(Variable v : bt.getInputVariables()) {
				if(v.isDataVariable()) {
					bindings.put(v.getName(), v.getBinding());
				}
			}
			String bstr = "";
			for(String v : bindings.keySet()) {
				bstr += bindings.get(v).toString() + ",";
			}
			if(!bindingstrs.containsKey(bstr)) {
				bindingstrs.put(bstr, true);
				blist.add(bindings);
			}
		}
		return blist;
	}
	
	private ArrayList<TreeMap<String, Binding>> getParameterBindings(ArrayList<Template> cts) {
		ArrayList<TreeMap<String, Binding>> bindings_b = new ArrayList<TreeMap<String, Binding>>();
		for(Template bt: cts) {
			TreeMap<String, Binding> binding_b = new TreeMap<String, Binding>();
			for(Variable v : bt.getInputVariables()) {
				if(v.isParameterVariable() && v.getBinding() != null) {
					binding_b.put(v.getName(), v.getBinding());
				}
			}
			bindings_b.add(binding_b);
		}
		
		// Expanding collections into multiple configurations
		// FIXME: Cannot handle parameter collections right now
		ArrayList<TreeMap<String, Binding>> bindings = new ArrayList<TreeMap<String, Binding>>();
		HashMap<String, Boolean> bstrs = new HashMap<String, Boolean>();
		while(!bindings_b.isEmpty()) {
			boolean hasSets = false;
			TreeMap<String, Binding> binding_b = bindings_b.remove(0);
			TreeMap<String, Binding> binding = new TreeMap<String, Binding>();

			for(String v : binding_b.keySet()) {
				Binding b = binding_b.get(v);
				if(b.isSet()) {
					for(WingsSet cb : b) {
						TreeMap<String, Binding> binding_x = new TreeMap<String, Binding>();
						for(String v1 : binding_b.keySet()) {
							Binding b1 = binding_b.get(v1);
							binding_x.put(v1,  b1);
						}
						binding_x.put(v, (Binding)cb);
						bindings_b.add(binding_x);
					}
					hasSets = true;
				}
				else {
					ValueBinding vb = (ValueBinding)b;
					binding.put(v, new ValueBinding(vb.getValue(), vb.getDatatype()));
				}
			}
			if(!hasSets) {
				String bstr = "";
				for(String v : binding.keySet()) {
					bstr += binding.get(v).toString() + ",";
				}
				if(!bstrs.containsKey(bstr)) {
					bstrs.put(bstr, true);
					bindings.add(binding);
				}
			}
		}
		
		return bindings;
	}

	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void addTemplateBindings(Template tpl, Map keyvals) {
		String pdtypejson = (String) ((Object[])keyvals.get("__paramdtypes"))[0];
		HashMap<String, String> paramdtypes = json.fromJson(pdtypejson, HashMap.class);

		String tplns = tpl.getNamespace();
		
		// Set Component bindings from cbindings
		String[] cbindingsarr = (String[]) keyvals.get("__cbindings");
		if (cbindingsarr != null && cbindingsarr.length > 0) {
			JsonElement cbindings = new JsonParser().parse(cbindingsarr[0]);
			JsonObject cbindingsobj = cbindings.getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : cbindingsobj.entrySet()) {
				String compid = entry.getKey();
				ComponentVariable cv = tpl.getComponentVariable(compid);
				if (cv == null)
					continue;

				// Create binding
				JsonElement bindingel = entry.getValue();
				Binding b = new Binding();
				if (bindingel.isJsonArray()) {
					for (JsonElement cb : bindingel.getAsJsonArray()) {
						b.add(new Binding(cb.getAsString()));
					}
				} else if (bindingel.isJsonPrimitive()) {
					b.setID(bindingel.getAsString());
				}
				cv.setBinding(b);
			}
		}

		// Set Data and Parameter Bindings from keyvals
		for(Object key : keyvals.keySet()) {
			if(((String)key).startsWith("__")) continue;
			String varid = tplns + key;
			Variable var = tpl.getVariable(varid);
			if(var != null) {
				Object[] vals = (Object[])keyvals.get(key);
				Binding b = var.isDataVariable() ? new Binding() : new ValueBinding();
				for(Object val : vals) {
					if(val.equals("")) continue;
					if(var.isDataVariable()) 
						b.add(new Binding(val.toString()));
					else {
						String dtype = paramdtypes.get(key);
						if(dtype == null)
							b.add(new ValueBinding(val.toString()));
						else
							b.add(new ValueBinding(val.toString(), dtype));
					}
				}	
				if(b.size() == 0)  continue;
				if(b.size() == 1) b = (Binding) b.get(0);
				tpl.setVariableBinding(var, b);
			}
		}
	}
	
	
	private Template createTemporaryTemplate(Template tpl) {
		// Create a temporary template
		String name = tpl.getName() + UuidGen.generateAUuid("");
		String newid = wliburl + "/" + name + ".owl#" + name;
		tpl.setID(newid);
		tc.saveTemplate(tpl);
		// Now re-read the temporary template
		return tc.getTemplate(newid);
	}
}