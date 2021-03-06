package com.tigertext.lucene;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.document.NumericField;
import org.apache.lucene.search.FieldCacheTermsFilter;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SortField;
import org.apache.lucene.spatial.geohash.GeoHashUtils;
import org.apache.lucene.spatial.tier.projections.CartesianTierPlotter;
import org.apache.lucene.spatial.tier.projections.IProjector;
import org.apache.lucene.spatial.tier.projections.SinusoidalProjector;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangDouble;
import com.ericsson.otp.erlang.OtpErlangFloat;
import com.ericsson.otp.erlang.OtpErlangInt;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangRangeException;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;

/**
 * Translator to convert erlang proplists into lucene docs
 * 
 * @author Fernando Benavides <elbrujohalcon@inaka.net>
 */
@SuppressWarnings("deprecation")
public class DocumentTranslator {
	private static final Logger jlog = Logger
			.getLogger(DocumentTranslator.class.getName());

	/**
	 * Highest possible tier for GEO fields
	 */
	public static final int MAX_TIER = 20;
	/**
	 * Lowest possible tier for GEO fields
	 */
	public static final int MIN_TIER = 4;

	private Map<String, FieldType> fields;

	/**
	 * @author Fernando Benavides <elbrujohalcon@inaka.net> Allowed field types
	 */
	public enum FieldType {
		/**
		 * Strings
		 */
		STRING,
		/**
		 * Integers
		 */
		INT,
		/**
		 * Longs
		 */
		LONG,
		/**
		 * Floating point numbers
		 */
		FLOAT,
		/**
		 * Doubles
		 */
		DOUBLE,
		/**
		 * Geographic coordinates
		 */
		GEO,
		/**
		 * Atoms
		 */
		ATOM
	}

	/**
	 * The field type was not in the {@link FieldType} enum
	 * 
	 * @author Fernando Benavides <elbrujohalcon@inaka.net>
	 */
	public class UnsupportedFieldTypeException extends Exception {
		/**
		 * @param class1
		 *            Type of the field
		 */
		public UnsupportedFieldTypeException(
				Class<? extends OtpErlangObject> class1) {
			super("Unsupported field type: " + class1);
		}

		private static final long serialVersionUID = 8966657853257773634L;
	}

	/**
	 * Default constructor
	 */
	public DocumentTranslator() {
		this.fields = new HashMap<String, DocumentTranslator.FieldType>();
	}

	protected OtpErlangList convert(List<Document> docs, ScoreDoc[] hits) {
		OtpErlangObject[] values = new OtpErlangObject[docs.size()];
		for (int i = 0; i < docs.size(); i++) {
			List<Fieldable> fields = docs.get(i).getFields();
			for (int k = fields.size() - 1; k >= 0; k--) {
				if (fields.get(k).name().contains("`"))
					fields.remove(k);
			}
			OtpErlangObject[] props = new OtpErlangObject[fields.size() + 1];
			int j = 0;
			for (Fieldable field : fields) {
				OtpErlangAtom key = new OtpErlangAtom(field.name());
				OtpErlangObject value = parseField(field);
				props[j] = new OtpErlangTuple(new OtpErlangObject[] { key,
						value });
				j++;
			}
			props[j] = new OtpErlangTuple(new OtpErlangObject[] {
					new OtpErlangAtom("`score"),
					Float.isNaN(hits[i].score) ? new OtpErlangAtom("undefined")
							: new OtpErlangFloat(hits[i].score) });
			values[i] = new OtpErlangList(props);
		}
		OtpErlangList valuesAsList = new OtpErlangList(values);
		return valuesAsList;
	}

	private OtpErlangObject parseField(Fieldable field) {
		switch (getFieldType(field.name())) {
		case DOUBLE:
			return new OtpErlangDouble(Double.parseDouble(field.stringValue()));
		case FLOAT:
			return new OtpErlangFloat(Float.parseFloat(field.stringValue()));
		case INT:
			return new OtpErlangInt(Integer.parseInt(field.stringValue()));
		case LONG:
			return new OtpErlangLong(Long.parseLong(field.stringValue()));
		case ATOM:
			return new OtpErlangAtom(field.stringValue());
		case GEO:
			double[] latLong = GeoHashUtils.decode(field.stringValue());
			if (latLong.length != 2)
				return new OtpErlangString(field.stringValue());
			return new OtpErlangTuple(new OtpErlangObject[] {
					new OtpErlangAtom("geo"), new OtpErlangDouble(latLong[0]),
					new OtpErlangDouble(latLong[1]) });
		default:
			return new OtpErlangString(field.stringValue());
		}
	}

	/**
	 * Creates a new {@link SortField}
	 * 
	 * @param sortField
	 *            Field Name and direction
	 * @return a sorting field
	 */
	public SortField createSortField(OtpErlangObject sortField) {
		String fieldName = "";
		boolean reverse = false;
		if (sortField instanceof OtpErlangAtom) {
			fieldName = ((OtpErlangAtom) sortField).atomValue();
		} else {
			OtpErlangTuple fieldTuple = (OtpErlangTuple) sortField;
			fieldName = ((OtpErlangAtom) fieldTuple.elementAt(0)).atomValue();
			reverse = ((OtpErlangAtom) fieldTuple.elementAt(1)).atomValue()
					.equals("desc");
		}
		switch (this.getFieldType(fieldName)) {
		case DOUBLE:
			return new SortField(fieldName, SortField.DOUBLE, reverse)
					.setMissingValue(Double.POSITIVE_INFINITY);
		case FLOAT:
			return new SortField(fieldName, SortField.FLOAT, reverse)
					.setMissingValue(Float.POSITIVE_INFINITY);
		case INT:
			return new SortField(fieldName, SortField.INT, reverse)
					.setMissingValue(Integer.MAX_VALUE);
		case LONG:
			return new SortField(fieldName, SortField.LONG, reverse)
					.setMissingValue(Long.MAX_VALUE);
		default:
			return new SortField(fieldName + "`sort",
					new MissingLastStringOrdValComparatorSource(), reverse);
		}
	}

	/**
	 * Returns a filter for the specified field and values
	 * 
	 * @return a FieldCacheTermsFilter
	 * @throws UnsupportedFieldTypeException
	 */
	public Filter createFilter(OtpErlangAtom otpFieldName,
			OtpErlangList otpFieldValues) throws UnsupportedFieldTypeException {
		String fieldName = otpFieldName.atomValue();
		String[] fieldValues = new String[otpFieldValues.arity()];
		for (int i = 0; i < fieldValues.length; i++) {
			fieldValues[i] = ((OtpErlangString) otpFieldValues.elementAt(i))
					.stringValue();
		}
		return new FieldCacheTermsFilter(fieldName, fieldValues);
	}

	/**
	 * converts a list of erlang objects into a list of lucene documents
	 * 
	 * @param objects
	 *            original erlang proplists
	 * @return a list of lucene documents
	 * @throws UnsupportedFieldTypeException
	 *             if a proplist contains an invalid field
	 */
	public List<Document> convert(OtpErlangList objects)
			throws UnsupportedFieldTypeException {
		List<Document> docs = new ArrayList<Document>(objects.arity());
		for (OtpErlangObject object : objects) {
			docs.add(buildDoc(((OtpErlangList) object).elements()));
		}
		return docs;
	}

	private Document buildDoc(OtpErlangObject[] props)
			throws UnsupportedFieldTypeException {
		Document doc = new Document();
		for (OtpErlangObject object : props) {
			OtpErlangTuple prop = (OtpErlangTuple) object;
			String key = ((OtpErlangAtom) prop.elementAt(0)).atomValue();
			OtpErlangObject value = prop.elementAt(1);

			addField(doc, key, value);
		}
		return doc;
	}

	protected void addField(Document doc, String key, OtpErlangObject value)
			throws UnsupportedFieldTypeException {
		try {
			this.getClass()
					.getDeclaredMethod("addField", Document.class,
							String.class, value.getClass())
					.invoke(this, doc, key, value);
		} catch (InvocationTargetException ite) {
			if (ite.getTargetException() instanceof UnsupportedFieldTypeException) {
				throw (UnsupportedFieldTypeException) ite.getTargetException();
			} else {
				jlog.severe("Invoke problems: " + ite);
				ite.printStackTrace();
				throw new UnsupportedFieldTypeException(value.getClass());
			}
		} catch (NoSuchMethodException e) {
			throw new UnsupportedFieldTypeException(value.getClass());
		} catch (Exception e) {
			jlog.severe("Invoke problems: " + e);
			e.printStackTrace();
			throw new UnsupportedFieldTypeException(value.getClass());
		}
	}

	/**
	 * adds a field to a document
	 * 
	 * @param doc
	 *            a Lucene document
	 * @param key
	 *            field name
	 * @param value
	 *            field value
	 * @throws UnsupportedFieldTypeException
	 *             if the value has an unsupported type
	 */
	public void addField(Document doc, String key, OtpErlangTuple value)
			throws UnsupportedFieldTypeException {
		if (value.arity() == 3
				&& value.elementAt(0) instanceof OtpErlangAtom
				&& ((OtpErlangAtom) value.elementAt(0)).atomValue().equals(
						"geo")) {
			addField(doc, key + "`lat", value.elementAt(1));
			addField(doc, key + "`lng", value.elementAt(2));

			double lat = ((OtpErlangDouble) value.elementAt(1)).doubleValue();
			double lng = ((OtpErlangDouble) value.elementAt(2)).doubleValue();
			String stringValue = GeoHashUtils.encode(lat, lng);

			addField(doc, key + "`latr", Math.toRadians(lat));
			addField(doc, key + "`lngr", Math.toRadians(lng));

			IProjector projector = new SinusoidalProjector();
			for (int tier = MIN_TIER; tier <= MAX_TIER; tier++) {
				CartesianTierPlotter ctp = new CartesianTierPlotter(tier,
						projector, key + "`tier_");
				addField(doc, key + "`tier_" + tier, ctp.getTierBoxId(lat, lng));
			}

			doc.add(new Field(key, stringValue, Field.Store.YES,
					Field.Index.ANALYZED));
			doc.add(new Field(key + "`sort", stringValue, Field.Store.NO,
					Field.Index.NOT_ANALYZED));
			this.fields.put(key, FieldType.GEO);

		} else {
			throw new UnsupportedFieldTypeException(value.getClass());
		}
	}

	private void addField(Document doc, String key, double value) {
		NumericField field = new NumericField(key, Field.Store.YES, true);
		doc.add(field.setDoubleValue(value));
		this.fields.put(key, FieldType.DOUBLE);
	}

	/**
	 * adds a double field to the document
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 */
	public void addField(Document doc, String key, OtpErlangDouble value) {
		addField(doc, key, value.doubleValue());
	}

	/**
	 * adds a float field to the document
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 * @throws UnsupportedFieldTypeException
	 *             if value can't be converted to float
	 */
	public void addField(Document doc, String key, OtpErlangFloat value)
			throws UnsupportedFieldTypeException {
		NumericField field = new NumericField(key, Field.Store.YES, true);
		try {
			field.setFloatValue(value.floatValue());
		} catch (OtpErlangRangeException e) {
			throw new UnsupportedFieldTypeException(value.getClass());
		}
		doc.add(field);
		this.fields.put(key, FieldType.FLOAT);
	}

	/**
	 * adds a long field to the document
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 */
	public void addField(Document doc, String key, OtpErlangLong value) {
		NumericField field = new NumericField(key, Field.Store.YES, true);
		field.setLongValue(value.longValue());
		doc.add(field);
		this.fields.put(key, FieldType.LONG);
	}

	/**
	 * adds an integer field to the document
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 * @throws UnsupportedFieldTypeException
	 *             if the value can't be converted to integer
	 */
	public void addField(Document doc, String key, OtpErlangInt value)
			throws UnsupportedFieldTypeException {
		try {
			NumericField field = new NumericField(key, Field.Store.YES, true);
			field.setIntValue(value.intValue());
			doc.add(field);
			this.fields.put(key, FieldType.INT);
		} catch (OtpErlangRangeException e) {
			throw new UnsupportedFieldTypeException(value.getClass());
		}
	}

	/**
	 * adds an empty string field to the document (because empty come as empty
	 * lists from erlang)
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 * @throws UnsupportedFieldTypeException
	 *             if the list is not empty
	 */
	public void addField(Document doc, String key, OtpErlangList value)
			throws UnsupportedFieldTypeException {
		if (value.arity() == 0) {
			doc.add(new Field(key, "", Field.Store.YES,
					Field.Index.NOT_ANALYZED));
			this.fields.put(key, FieldType.STRING);
		} else {
			throw new UnsupportedFieldTypeException(value.getClass());
		}
	}

	/**
	 * adds an atom field to the document
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 */
	public void addField(Document doc, String key, OtpErlangAtom value) {
		doc.add(new Field(key, value.atomValue(), Field.Store.YES,
				Field.Index.ANALYZED));
		doc.add(new Field(key + "`sort", value.atomValue().toLowerCase(),
				Field.Store.NO, Field.Index.NOT_ANALYZED));
		this.fields.put(key, FieldType.ATOM);
	}

	private void addField(Document doc, String key, String value) {
		doc.add(new Field(key, value, Field.Store.YES, Field.Index.ANALYZED));
		doc.add(new Field(key + "`sort", value.toLowerCase(), Field.Store.NO,
				Field.Index.NOT_ANALYZED));
		this.fields.put(key, FieldType.STRING);
	}

	/**
	 * adds a string field to the document
	 * 
	 * @param doc
	 *            Lucene document
	 * @param key
	 *            Field name
	 * @param value
	 *            Field value
	 */
	public void addField(Document doc, String key, OtpErlangString value) {
		addField(doc, key, value.stringValue());
	}

	/**
	 * Looks up the field definition and returns its type
	 * 
	 * @param fieldName
	 *            Field Name
	 * @return Field Type
	 */
	public FieldType getFieldType(String fieldName) {
		FieldType type = this.fields.get(fieldName);
		return type == null ? FieldType.STRING : type;
	}
}
