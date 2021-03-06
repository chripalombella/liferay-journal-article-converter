package com.github.davidepastore.liferay.converter;

import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import com.github.davidepastore.liferay.annotation.JournalArticleField;
import com.github.davidepastore.liferay.model.DDMDocumentAndMedia;
import com.github.davidepastore.liferay.model.DDMGeolocation;
import com.github.davidepastore.liferay.model.DDMImage;
import com.github.davidepastore.liferay.util.JsonUtil;
import com.github.davidepastore.liferay.util.SimpleLocaleUtil;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.journal.model.JournalArticle;

/**
 * The Convertible Journal Article class that contains the methods to convert a {@link JournalArticle}
 * to an {@link Object}. You should use {@link JournalArticleField} annotations to
 * be sure that your data is correctly mapped.
 * @author Davide Pastore
 *
 */
public abstract class ConvertibleJournalArticle {

	private static Log log = LogFactoryUtil.getLog(ConvertibleJournalArticle.class);

	/**
	 * Create the object from the given {@link JournalArticle} instance.
	 * @param journalArticle The {@link JournalArticle} instance.
	 * @throws Exception
	 */
	public void fromJournalArticle(JournalArticle journalArticle) throws Exception{
		fromJournalArticle(journalArticle, SimpleLocaleUtil.buildLocale(journalArticle.getDefaultLanguageId()));
	}

	/**
	 * Create the object from the given {@link JournalArticle} and {@link Locale}.
	 * @param journalArticle The {@link JournalArticle} instance.
	 * @param locale The {@link Locale} to use to read from the {@link JournalArticle}.
	 * @throws Exception
	 */
	public void fromJournalArticle(JournalArticle journalArticle, Locale locale) throws Exception{
		log.debug("Not localized content: " + journalArticle.getContent());
		log.debug("Localized content: " + journalArticle.getContentByLocale(locale.getLanguage()));
		String content = journalArticle.getContentByLocale(locale.getLanguage());
		org.jsoup.nodes.Document document = Jsoup.parse(content, "", Parser.xmlParser());
		Elements elements = document.select("root > dynamic-element");
		setValueFromElements(elements, this, journalArticle.getTitle(locale));
		log.debug("Object: " + this);
	}

	/**
	 * Set value from the {@link Elements} instance.
	 * @param elements The {@link Elements} on which search the value.
	 * @param object The object on which set the fields.
	 * @param title The title.
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InstantiationException
	 * @throws FileNotFoundException 
	 * @throws IndexOutOfBoundsException 
	 * @throws JsonIOException 
	 * @throws JsonSyntaxException 
	 */
	protected void setValueFromElements(Elements elements, Object object, String title) throws IllegalArgumentException, IllegalAccessException, InstantiationException, JsonSyntaxException, JsonIOException, IndexOutOfBoundsException, FileNotFoundException{
		List<String> names = getDynamicElementNames(elements);
		Object value = null;
		for (String name : names) {
			Elements elementsWithName = elements.select("[name=" + name + "]");
			List<Object> listValue = new ArrayList<Object>();
			int counter = 0;
			int size = elementsWithName.size();
			boolean isList = isListInObject(name, object) || size > 1;
			for (Element element : elementsWithName) {
				String type = element.attr("type");
				if(type.equals("list")){
					isList = false;
				}
				value = getObjectValue(element, object, title);
				boolean setValue = true;
				if(isList){
					counter++;
					listValue.add(value);
					if(counter == size){
						setValue = true;
						value = listValue;
					} else {
						setValue = false;
					}
				}

				if(setValue){
					List<Field> linkedFields = getLinkedFields(name, object.getClass());
					for (Field linkedField : linkedFields) {
						linkedField.setAccessible(true);
						linkedField.set(object, value);
					}
				}

				List<Field> titleFields = getTitleFields();
				for (Field titleField : titleFields) {
					titleField.setAccessible(true);
					titleField.set(object, title);
				}

				List<Field> baseFields = getBaseFields(object.getClass());
				for (Field baseField : baseFields) {
					baseField.setAccessible(true);
					Object baseValue = getObjectValue(elements.first().parent().select("> dynamic-content").first(), object, title);
					baseField.set(object, baseValue);
				}
			}
		}
	}

	/**
	 * Get the object value of the given {@link Element}.
	 * @param element The {@link Element} that contains the XML structure (dynamic-element or dynamic-content).
	 * @param object The object on which set the value.
	 * @param title The title to use.
	 * @return Returns the object with the found value (it also handles nested properties).
	 * @throws InstantiationException
	 * @throws IllegalAccessException
	 * @throws FileNotFoundException 
	 * @throws JsonIOException 
	 * @throws JsonSyntaxException 
	 */
	protected Object getObjectValue(Element element, Object object, String title) throws InstantiationException, IllegalAccessException, NumberFormatException, IndexOutOfBoundsException, JsonSyntaxException, JsonIOException, FileNotFoundException {
		Object value = null;
		String type = element.attr("type");
		String stringValue = element.text();
		//If this is already a dynamic-content, it means it is a base nested element
		if(element.tagName().equals("dynamic-content")){
			type = element.parent().attr("type");
		}

		Elements nestedElements = element.children().select("dynamic-element");
		if(!nestedElements.isEmpty()){
			//Handle nested type
			String parentName = element.attr("name");
			//log.info("Parent name: " + parentName);
			List<Field> fields = getLinkedFields(parentName, object.getClass());
			Object nestedObject;
			for (Field field : fields) {
				field.setAccessible(true);
				Class<?> clazzType = field.getType();
				log.debug("clazzType: " + clazzType);
				if(clazzType.getName().equals("java.util.List")){
					log.debug("This seems a list!");
					ParameterizedType listType = (ParameterizedType) field.getGenericType();
					clazzType = (Class<?>) listType.getActualTypeArguments()[0];
				}
				nestedObject = clazzType.newInstance();
				setValueFromElements(nestedElements, nestedObject, title);
				value = nestedObject;
			}
		} else if(type.equals("text")){
			value = stringValue;
		} else if(type.equals("boolean")){
			value = Boolean.parseBoolean(stringValue);
		} else if(type.equals("ddm-date")){
			value = new Date(Long.parseLong(stringValue));
		} else if(type.equals("ddm-decimal")){
			value = new Double(stringValue);
		} else if(type.equals("image")){
			DDMImage ddmImage = JsonUtil.getDDMImage(stringValue);
			value = ddmImage;
		} else if(type.equals("document_library")){
			DDMDocumentAndMedia ddmDocumentAndMedia = JsonUtil.getDDMDocumentAndMedia(stringValue);
			value = ddmDocumentAndMedia;
		} else if(type.equals("ddm-integer")){
			value = Integer.parseInt(stringValue);
		} else if(type.equals("link_to_layout")){
			value = stringValue;
		} else if(type.equals("ddm-number")){
			value = new BigDecimal(stringValue);
		} else if(type.equals("radio")){
			value = stringValue.substring(2, stringValue.length() - 2);
		} else if(type.equals("list")){
			Elements options = element.select("dynamic-content option");
			if(options.isEmpty()){
				//Single option
				value = stringValue;
			} else {
				//Multiple option
				List<String> list = new ArrayList<String>();
				for (Element option : options) {
					list.add(option.text());
				}
				value = list;
			}
		} else if(type.equals("text_box")){
			value = stringValue;
		} else if(type.equals("text_area")){
			value = stringValue;
		} else if(type.equals("ddm-geolocation")) {
			DDMGeolocation ddmGeolocation = JsonUtil.getDDMGeolocation(stringValue);
			value = ddmGeolocation;
		}

		return value;
	}

	/**
	 * Get the linked {@link Field} with this class.
	 * @param name The name of the field.
	 * @param clazz The {@link Class} to use.
	 * @return Returns the linked {@link Field}.
	 *
	 * @author Christian Palombella
	 */
	protected Field getLinkedField(String name, Class<?> clazz) {
		for (Field field : clazz.getDeclaredFields()) {
			if (field.isAnnotationPresent(JournalArticleField.class)) {
				JournalArticleField annotation = field.getAnnotation(JournalArticleField.class);
				String fieldName = annotation.name();
				if(name.equals(fieldName)){
					return field;
				}
			}
		}
		return null;
	}

	/**
	 * Get the {@link List} of linked {@link Field} with this class.
	 * @param name The name of the field.
	 * @param clazz The {@link Class} to use.
	 * @return Returns the {@link List} of linked {@link Field}.
	 */
	protected List<Field> getLinkedFields(String name, Class<?> clazz){
		List<Field> fields = new ArrayList<Field>();
		for (Field field : clazz.getDeclaredFields()) {
			if (field.isAnnotationPresent(JournalArticleField.class)) {
				JournalArticleField annotation = field.getAnnotation(JournalArticleField.class);
				String fieldName = annotation.name();
				if(name.equals(fieldName)){
					fields.add(field);
				}
			}
		}
		return fields;
	}

	/**
	 * Get the {@link List} of {@link Field} that contains the title.
	 * @return Returns the {@link List} of {@link Field} that contains the title.
	 */
	protected List<Field> getTitleFields(){
		List<Field> fields = new ArrayList<Field>();
		for (Field field : this.getClass().getDeclaredFields()) {
			if (field.isAnnotationPresent(JournalArticleField.class)) {
				JournalArticleField annotation = field.getAnnotation(JournalArticleField.class);
				if(annotation.title()){
					fields.add(field);
				}
			}
		}
		return fields;
	}

	/**
	 * Get the {@link List} of {@link Field} that should be associated with the base value.
	 * @param clazz The {@link Class} to use.
	 * @return Returns the {@link List} of {@link Field} that should be associated with the base value.
	 */
	protected List<Field> getBaseFields(Class<?> clazz){
		List<Field> fields = new ArrayList<Field>();
		for (Field field : clazz.getDeclaredFields()) {
			if (field.isAnnotationPresent(JournalArticleField.class)) {
				JournalArticleField annotation = field.getAnnotation(JournalArticleField.class);
				if(annotation.base()){
					fields.add(field);
				}
			}
		}
		return fields;
	}


	/**
	 * Get a {@link List} of all the dynamic element.
	 * @param elements The {@link Elements}.
	 * @return Returns a {@link List} of all the dynamic elements.
	 */
	protected List<String> getDynamicElementNames(Elements elements){
		List<String> names = new ArrayList<String>();
		for (Element element : elements) {
			String name = element.attr("name");
			if(!names.contains(name)){
				names.add(name);
			}
		}
		return names;
	}

	/**
	 * Check if the name of {@link Element} is a type of {@link List} in the given object
	 * @param name of the element
	 * @param object The object on which set the value.
	 * @return boolean
	 *
	 * @author Christian Palombella
	 */
	protected boolean isListInObject(String name, Object object)  {

		Field field = getLinkedField(name, object.getClass());

		if(field != null) {
			field.setAccessible(true);
			Class<?> clazzType = field.getType();
			log.debug("clazzType: " + clazzType);
			if(clazzType.getName().equals("java.util.List")){
				log.debug("This seems a list!");

				return true;
			}
		}
		return false;
	}

}
