/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2016 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.hk2.xml.internal;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.Unmarshaller;
import javax.xml.bind.Unmarshaller.Listener;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.glassfish.hk2.utilities.general.GeneralUtilities;
import org.glassfish.hk2.utilities.reflection.ClassReflectionHelper;
import org.glassfish.hk2.xml.jaxb.internal.BaseHK2JAXBBean;
import org.glassfish.hk2.xml.spi.Model;

/**
 * @author jwells
 *
 */
public class XmlStreamImpl {
    @SuppressWarnings("unchecked")
    public static <T> T parseRoot(XmlServiceImpl xmlService,
            Model rootModel,
            XMLStreamReader reader,
            Unmarshaller.Listener listener) throws Exception {
        Class<?> rootProxyClass = rootModel.getProxyAsClass();
        
        ClassReflectionHelper classReflectionHelper = xmlService.getClassReflectionHelper();
        
        BaseHK2JAXBBean hk2Root = Utilities.createBean(rootProxyClass);
        hk2Root._setClassReflectionHelper(classReflectionHelper);
        
        Map<ReferenceKey, BaseHK2JAXBBean> referenceMap = new HashMap<ReferenceKey, BaseHK2JAXBBean>();
        List<UnresolvedReference> unresolved = new LinkedList<UnresolvedReference>();
        
        while(reader.hasNext()) {
            int event = reader.next();
            
            switch(event) {
            case XMLStreamConstants.START_ELEMENT:
                handleElement(hk2Root, null, reader, classReflectionHelper, listener, referenceMap, unresolved);
                
                break;
            case XMLStreamConstants.END_DOCUMENT:
                // Resolve any forward references
                for (UnresolvedReference unresolvedRef : unresolved) {
                    ReferenceKey key = new ReferenceKey(unresolvedRef.type, unresolvedRef.xmlID);
                    BaseHK2JAXBBean reference = referenceMap.get(key);
                    if (reference == null) {
                        throw new IllegalStateException("No Reference was found for " + unresolvedRef);
                    }
                    
                    BaseHK2JAXBBean unfinished = unresolvedRef.unfinished;
                    unfinished._setProperty(unresolvedRef.propertyName, reference);
                }
                
                return (T) hk2Root;
            default:
                // Do nothing
            }
            
        }
        
        throw new IllegalStateException("Unexpected end of XMLReaderStream");
    }
    
    private static <T> void handleElement(BaseHK2JAXBBean target,
            BaseHK2JAXBBean parent,
            XMLStreamReader reader,
            ClassReflectionHelper classReflectionHelper,
            Listener listener,
            Map<ReferenceKey, BaseHK2JAXBBean> referenceMap,
            List<UnresolvedReference> unresolved) throws Exception {
        listener.beforeUnmarshal(target, parent);
        
        Map<String, List<BaseHK2JAXBBean>> listChildren = new HashMap<String, List<BaseHK2JAXBBean>>();
        Map<String, List<BaseHK2JAXBBean>> arrayChildren = new HashMap<String, List<BaseHK2JAXBBean>>();
        
        ModelImpl targetModel = target._getModel();
        Map<String, ChildDataModel> nonChildProperties = targetModel.getNonChildProperties();
        Map<String, ParentedModel> childProperties = targetModel.getChildrenByName();
        
        int numAttributes = reader.getAttributeCount();
        for (int lcv = 0; lcv < numAttributes; lcv++) {
            String attributeName = reader.getAttributeLocalName(lcv);
            String attributeValue = reader.getAttributeValue(lcv);
            
            ChildDataModel childDataModel = nonChildProperties.get(attributeName);
            if (childDataModel == null) continue;
            
            Class<?> childType = targetModel.getNonChildType(attributeName);
            
            Object convertedValue = Utilities.getDefaultValue(attributeValue, childType);
            target._setProperty(attributeName, convertedValue);
        }
        
        while(reader.hasNext()) {
            int event = reader.next();
            
            switch(event) {
            case XMLStreamConstants.START_ELEMENT:
                String elementTag = reader.getName().getLocalPart();
                
                ChildDataModel cdm = nonChildProperties.get(elementTag);
                if (cdm != null) {
                    String elementValue = advanceNonChildElement(reader);
                    
                    Class<?> childType = cdm.getChildTypeAsClass();
                    
                    if (!cdm.isReference()) {
                        Object convertedValue = Utilities.getDefaultValue(elementValue, childType);
                        
                        target._setProperty(elementTag, convertedValue);
                    }
                    else {
                        ReferenceKey referenceKey = new ReferenceKey(cdm.getChildType(), elementValue);
                        BaseHK2JAXBBean reference = referenceMap.get(referenceKey);
                        
                        if (reference != null) {
                            target._setProperty(elementTag, reference);
                        }
                        else {
                            unresolved.add(new UnresolvedReference(cdm.getChildType(),
                                    elementValue, elementTag, target));
                        }
                    }
                    
                    break;
                }
                
                ParentedModel informedChild = childProperties.get(elementTag);
                if (informedChild != null) {
                    ModelImpl grandChild = informedChild.getChildModel();
                    
                    BaseHK2JAXBBean hk2Root = Utilities.createBean(grandChild.getProxyAsClass());
                    hk2Root._setClassReflectionHelper(classReflectionHelper);
                    
                    handleElement(hk2Root, target, reader, classReflectionHelper, listener, referenceMap, unresolved);
                    
                    if (informedChild.getChildType().equals(ChildType.DIRECT)) {
                        target._setProperty(elementTag, hk2Root);
                    }
                    else if (informedChild.getChildType().equals(ChildType.LIST)) {
                        List<BaseHK2JAXBBean> cList = listChildren.get(elementTag);
                        if (cList == null) {
                            cList = new ArrayList<BaseHK2JAXBBean>();
                            listChildren.put(elementTag, cList);
                        }
                        cList.add(hk2Root);
                    }
                    else if (informedChild.getChildType().equals(ChildType.ARRAY)) {
                        List<BaseHK2JAXBBean> cList = arrayChildren.get(elementTag);
                        if (cList == null) {
                            cList = new LinkedList<BaseHK2JAXBBean>();
                            arrayChildren.put(elementTag, cList);
                        }
                        cList.add(hk2Root);
                    }
                    
                    break;
                }
                
                break;
            case XMLStreamConstants.CHARACTERS:
                // Do nothing
                break;
            case XMLStreamConstants.END_ELEMENT:
                for (Map.Entry<String, List<BaseHK2JAXBBean>> entry : listChildren.entrySet()) {
                    // Kind of cheating with the erasure, but hey, it works!
                    target._setProperty(entry.getKey(), entry.getValue());
                }
                
                for (Map.Entry<String, List<BaseHK2JAXBBean>> entry : arrayChildren.entrySet()) {
                    String childTag = entry.getKey();
                    ParentedModel pn = targetModel.getChild(childTag);
                    Class<?> childType = pn.getChildModel().getOriginalInterfaceAsClass();
                    
                    List<BaseHK2JAXBBean> individuals = entry.getValue();
                    
                    Object actualArray = Array.newInstance(childType, individuals.size());
                    
                    int index = 0;
                    for (BaseHK2JAXBBean individual : individuals) {
                        Array.set(actualArray, index++, individual);
                    }
                    
                    target._setProperty(childTag, actualArray);
                    
                }
                
                listener.afterUnmarshal(target, parent);
                
                // Put the finished product into the reference map
                String keyProp = target._getKeyPropertyName();
                if (keyProp != null) {
                    String keyVal = (String) target._getProperty(keyProp);
                    String myType = target._getModel().getOriginalInterface();
                    if (keyVal != null && myType != null) {
                        referenceMap.put(new ReferenceKey(myType, keyVal), target);
                    }
                }
                
                return;
            case XMLStreamConstants.COMMENT:
                break;
            default:
                // Do nothing
            }
            
        }
        
        
    }
    
    private static String advanceNonChildElement(XMLStreamReader reader) throws Exception {
        String retVal = null;
        
        while (reader.hasNext()) {
            int nextEvent = reader.next();
            switch (nextEvent) {
            case XMLStreamConstants.CHARACTERS:
                String text = reader.getText();
                // TODO:  To trim or not to trim
                retVal = text.trim();
                break;
            case XMLStreamConstants.END_ELEMENT:
                return retVal;
            default:
                // Everything else would be comments or other stuff
                // If not we have a bigger problem
            }
        }
        
        return retVal;
    }
    
    private static class ReferenceKey {
        private final String type;
        private final String xmlID;
        
        private ReferenceKey(String type, String xmlID) {
            this.type = type;
            this.xmlID = xmlID;
        }
        
        @Override
        public int hashCode() {
            return type.hashCode() ^ xmlID.hashCode();
        }
        
        @Override
        public boolean equals(Object o) {
            if (o == null) return false;
            if (!(o instanceof ReferenceKey)) return false;
            ReferenceKey other = (ReferenceKey) o;
            
            return GeneralUtilities.safeEquals(type, other.type) && GeneralUtilities.safeEquals(xmlID, other.xmlID);
        }
        
        @Override
        public String toString() {
            return "ReferenceKey(" + type + "," + xmlID + "," + System.identityHashCode(this) + ")";
        }
    }
    
    private static class UnresolvedReference {
        private final String type;
        private final String xmlID;
        private final String propertyName;
        private final BaseHK2JAXBBean unfinished;
        
        private UnresolvedReference(String type, String xmlID, String propertyName, BaseHK2JAXBBean unfinished) {
            this.type = type;
            this.xmlID = xmlID;
            this.propertyName = propertyName;
            this.unfinished = unfinished;
        }
        
        @Override
        public String toString() {
            return "UnresolvedReference(" + type + "," + xmlID + "," + propertyName + "," + unfinished + "," + System.identityHashCode(this) + ")";
        }
        
    }

}
