/**
 * Tools for serializing components, stylesheets, and property instances to and from XML.
 */
EchoSerial = { 

    _propertyTranslatorMap: { },
    
    addPropertyTranslator: function(type, propertyTranslator) {
        EchoSerial._propertyTranslatorMap[type] = propertyTranslator;
    },
    
    getPropertyTranslator: function(type) {
        return this._propertyTranslatorMap[type];
    },
    
    /**
     * Deserializes an XML representation of a component into a component instance.
     * Any child components will be added to the created component instance.
     * Events properties will be registered with the client by invoking the
     * "addComponentListener()" method on the provided 'client', passing in
     * the properties 'component' (the component instance) and 'event' (the event
     * type as a string).
     * 
     * @param client the containing client
     * @param componentElement the 'c' DOM element to deserialize
     * @return the instantiated component.
     */
    loadComponent: function(client, componentElement, referenceMap) {
        if (!componentElement.nodeName == "c") {
            throw new Error("Element is not a component.");
        }
        var type = componentElement.getAttribute("t");
        var id = componentElement.getAttribute("i");
    
        var component = EchoApp.ComponentFactory.newInstance(type, id);
    
        if (componentElement.getAttribute("en") == "false") {
            component.setEnabled(false);
        }
        
        var styleName = componentElement.getAttribute("s");
        if (styleName) {
            component.setStyleName(styleName);
        }
        
        var styleData = component.getLocalStyleData();
        
        var element = componentElement.firstChild;
        while (element) {
            if (element.nodeType == 1) {
                switch (element.nodeName) {
                case "c": // Child Component
                    var childComponent = this.loadComponent(client, element, referenceMap);
                    component.add(childComponent);
                    break;
                case "p": // Property
                    this.loadProperty(client, element, component, styleData, referenceMap);
                    break;
                case "e": // Event
                    this._loadComponentEvent(client, element, component);
                    break;
                }
            }
            element = element.nextSibling;
        }
        
        return component;
    },
    
    _loadComponentEvent: function(client, eventElement, component) {
        var eventType = eventElement.getAttribute("t");
        client.addComponentListener(component, eventType);
    },
    
    /**
     * Deserializes an XML representation of a property into an instance,
     * and assigns it to the specified object.
     * 
     * @param client the containing client
     * @param {Element} propertyElement the property element to parse
     * @param object the object on which the properties should be set (this object
     *        must contain setProperty() and setIndexedProperty() methods
     * @param styleData (optional) an associative array on which properties can
     *        be directly set
     * @param referenceMap (optional) an associative array containing previously
     *        loaded reference-based properties
     */
    loadProperty: function(client, propertyElement, object, styleData, referenceMap) {
        var propertyName = propertyElement.getAttribute("n");
        var propertyType = propertyElement.getAttribute("t");
        var propertyIndex = propertyElement.getAttribute("x");
        var propertyValue;
        
        if (propertyType) {
            // Invoke custom property processor.
            var translator = EchoSerial._propertyTranslatorMap[propertyType];
            if (!translator) {
                throw new Error("Translator not available for property type: " + propertyType);
            }
            propertyValue = translator.toProperty(client, propertyElement);
        } else if (referenceMap) {
            var propertyReference = propertyElement.getAttribute("r");
            if (!propertyReference) {
                throw new Error("No property type specified for property: " + propertyName + referenceMap);
            }
            propertyValue = referenceMap[propertyReference];
        } else {
            throw new Error("No property type specified for property: " + propertyName + referenceMap);
        }
        
        if (propertyName) {
            if (styleData) {
                if (propertyIndex == null) {
                    styleData[propertyName] = propertyValue;
                } else {
                    var indexValues = styleData[propertyName];
                    if (!indexValues) {
                        indexValues = [];
                        styleData[propertyName] = indexValues;
                    }
                    indexValues[propertyIndex] = propertyValue;
                }
            } else {
                // Property has property name: invoke set(Indexed)Property.
                if (propertyIndex == null) {
                    object.setProperty(propertyName, propertyValue);
                } else {
                    object.setIndexedProperty(propertyName, propertyIndex, propertyValue);
                }
            }
        } else {
            // Property has method name: invoke method.
            var propertyMethod = propertyElement.getAttribute("m");
            if (propertyIndex == null) {
                object[propertyMethod](propertyValue);
            } else {
                object[propertyMethod](propertyIndex, propertyValue);
            }
        }
    },
    
    /**
     * Deserializes an XML representation of a style sheet into a
     * StyleSheet instance.
     */
    loadStyleSheet: function(client, ssElement, referenceMap) {
        var styleSheet = new EchoApp.StyleSheet();
        
        var ssChild = ssElement.firstChild;
        while (ssChild) {
            if (ssChild.nodeType == 1) {
                if (ssChild.nodeName == "s") {
                    var styleData = {};
                    var style = new EchoApp.Style(styleData);
                    var sChild = ssChild.firstChild;
                    while (sChild) {
                        if (sChild.nodeType == 1) {
                            switch (sChild.nodeName) {
                            case "p":
                                this.loadProperty(client, sChild, style, styleData, referenceMap);
                                break;
                            }
                        }
                        sChild = sChild.nextSibling;
                    }
                    styleSheet.setStyle(ssChild.getAttribute("n"), ssChild.getAttribute("t"), style);
                }
            }
            ssChild = ssChild.nextSibling;
        }
        return styleSheet;
    },
    
    /**
     * Serializes a property value into an XML representation.
     */
    storeProperty: function(client, propertyElement, propertyValue) {
        Core.Debug.consoleWrite("Storing property:" + propertyValue);
        if (typeof (propertyValue) == "object") {
            if (!propertyValue.className) {
                throw new Error("propertyValue \"" + propertyValue 
                        + "\" does not provide className property, cannot determine translator.");
            }
            var translator = this._propertyTranslatorMap[propertyValue.className];
            if (!translator || !translator.toXml) {
                throw new Error("No to-XML translator available for class name: " + propertyValue.className);
                //FIXME. silently ignore and return may be desired behavior.
            }
            translator.toXml(client, propertyElement, propertyValue);
        } else {
            // call toString here, IE will otherwise convert boolean values to integers
            var value = propertyValue == null ? propertyValue : propertyValue.toString(); 
            propertyElement.setAttribute("v", value);
        }
    }
};

/**
 * Namespace for property translator implementations.
 */
EchoSerial.PropertyTranslator = { };

/**
 * Null PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Null = {

    toProperty: function(client, propertyElement) {
        return null;
    }
};

EchoSerial.addPropertyTranslator("0", EchoSerial.PropertyTranslator.Null);

/**
 * Boolean PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Boolean = {

    toProperty: function(client, propertyElement) {
        return propertyElement.getAttribute("v") == "true";
    }
};

EchoSerial.addPropertyTranslator("b", EchoSerial.PropertyTranslator.Boolean);

/**
 * Float PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Float = {

    toProperty: function(client, propertyElement) {
        return parseFloat(propertyElement.getAttribute("v"));
    }
};

EchoSerial.addPropertyTranslator("f", EchoSerial.PropertyTranslator.Float);

/**
 * Integer PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Integer = { 

    toProperty: function(client, propertyElement) {
        return parseInt(propertyElement.getAttribute("v"));
    }
};

EchoSerial.addPropertyTranslator("i", EchoSerial.PropertyTranslator.Integer);

/**
 * String PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.String = {

    toProperty: function(client, propertyElement) {
    	var firstChild = propertyElement.firstChild;
    	return firstChild ? firstChild.nodeValue : "";
    }
};

EchoSerial.addPropertyTranslator("s", EchoSerial.PropertyTranslator.String);

/**
 * Map (Associative Array) PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Map = {

    toProperty: function(client, propertyElement) {
        var mapObject = {};
        var element = propertyElement.firstChild;
        while (element) {
            if (element.nodeType != 1) {
                continue;
            }
    
            EchoSerial.loadProperty(client, element, null, mapObject, null);
            element = element.nextSibling;
        }
        return mapObject;
    }
};

EchoSerial.addPropertyTranslator("map", EchoSerial.PropertyTranslator.Map);

/**
 * Alignment PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Alignment = {

    toProperty: function(client, propertyElement) {
        var element = WebCore.DOM.getChildElementByTagName(propertyElement, "a");
        var h, v;
        switch (element.getAttribute("h")) {
        case "leading":  h = EchoApp.Alignment.LEADING;  break;
        case "trailing": h = EchoApp.Alignment.TRAILING; break;
        case "left":     h = EchoApp.Alignment.LEFT;     break;
        case "center":   h = EchoApp.Alignment.CENTER;   break;
        case "right":    h = EchoApp.Alignment.RIGHT;    break;
        default:         h = EchoApp.Alignment.DEFAULT;
        }
        switch (element.getAttribute("v")) {
        case "top":      v = EchoApp.Alignment.TOP;      break;
        case "center":   v = EchoApp.Alignment.CENTER;   break;
        case "bottom":   v = EchoApp.Alignment.BOTTOM;   break;
        default:         v = EchoApp.Alignment.DEFAULT;  
        }
        return new EchoApp.Alignment(h, v);
    }
};

EchoSerial.addPropertyTranslator("Alignment", EchoSerial.PropertyTranslator.Alignment);

/**
 * Border PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Border = {

    toProperty: function(client, propertyElement) {
        var value = propertyElement.getAttribute("v");
        if (value) {
            return new EchoApp.Border(value);
        } else {
            var element = WebCore.DOM.getChildElementByTagName(propertyElement, "b");
            var sides = [];
            
            value = element.getAttribute("t");
            if (value) {
                sides.push(new EchoApp.Border.Side(value));
                value = element.getAttribute("r");
                if (value) {
                    sides.push(new EchoApp.Border.Side(value));
                    value = element.getAttribute("b");
                    if (value) {
                        sides.push(new EchoApp.Border.Side(value));
                        value = element.getAttribute("l");
                        if (value) {
                            sides.push(new EchoApp.Border.Side(value));
                        }
                    }
                }
            } else {
                throw new Error("Invalid multi-sided border: no sides set.");
            }
            return new EchoApp.Border(sides);
        }
    }
};

EchoSerial.addPropertyTranslator("Border", EchoSerial.PropertyTranslator.Border);

/**
 * Color PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Color = {

    toProperty: function(client, propertyElement) {
        return new EchoApp.Color(propertyElement.getAttribute("v"));
    },
    
    toXml: function(client, propertyElement, propertyValue) {
        propertyElement.setAttribute("v", propertyValue.value);
    }
};

EchoSerial.addPropertyTranslator("Color", EchoSerial.PropertyTranslator.Color);

/**
 * Extent PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Extent = {

    toProperty: function(client, propertyElement) {
        return new EchoApp.Extent(propertyElement.getAttribute("v"));
    },
    
    toXml: function(client, propertyElement, propertyValue) {
        propertyElement.setAttribute("v", propertyValue.toString());
    }
};

EchoSerial.addPropertyTranslator("Extent", EchoSerial.PropertyTranslator.Extent);

/**
 * FillImage PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.FillImage = {

    toProperty: function(client, propertyElement) {
        var element = WebCore.DOM.getChildElementByTagName(propertyElement, "fi");
        return this._parseElement(client, element);
    },
    
    _parseElement: function(client, fiElement) {
        var url = fiElement.getAttribute("u");
        if (client.decompressUrl) {
            url = client.decompressUrl(url);
        }
        var repeat = fiElement.getAttribute("r");
        switch (repeat) {
        case "0": repeat = EchoApp.FillImage.NO_REPEAT; break;
        case "xy": repeat = EchoApp.FillImage.REPEAT; break;
        case "x": repeat = EchoApp.FillImage.REPEAT_HORIZONTAL; break;
        case "y": repeat = EchoApp.FillImage.REPEAT_VERTICAL; break;
        }
        var x = fiElement.getAttribute("x");
        var y = fiElement.getAttribute("y");
        return new EchoApp.FillImage(url, repeat, x, y);
    }
};

EchoSerial.addPropertyTranslator("FillImage", EchoSerial.PropertyTranslator.FillImage);

/**
 * FillImageBorder PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.FillImageBorder = {

    toProperty: function(client, propertyElement) {
        var element = WebCore.DOM.getChildElementByTagName(propertyElement, "fib");
        return EchoSerial.PropertyTranslator.FillImageBorder._parseElement(client, element);
    },
    
    _parseElement: function(client, fibElement) {
        var contentInsets = fibElement.getAttribute("ci");
        contentInsets = contentInsets ? new EchoApp.Insets(contentInsets) : null;
        var borderInsets = fibElement.getAttribute("bi");
        borderInsets = borderInsets ? new EchoApp.Insets(borderInsets) : null;
        var borderColor = fibElement.getAttribute("bc");
        borderColor = borderColor ? new EchoApp.Color(borderColor) : null;
        var fillImages = [];
        
        var element = fibElement.firstChild;
        while(element) {
            if (element.nodeType == 1) {
                if (element.nodeName == "fi") {
                    fillImages.push(EchoSerial.PropertyTranslator.FillImage._parseElement(client, element));
                } else if (element.nodeName == "null-fi") {
                    fillImages.push(null);
                }
            }
            element = element.nextSibling;
        }
        if (fillImages.length != 8) {
    	    throw new Error("Invalid FillImageBorder image count: " + fillImages.length);
        }
    
        return new EchoApp.FillImageBorder(borderColor, borderInsets, contentInsets, fillImages);
    }
};

EchoSerial.addPropertyTranslator("FillImageBorder", EchoSerial.PropertyTranslator.FillImageBorder);

/**
 * Font PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Font = {

    toProperty: function(client, propertyElement) {
        var element = WebCore.DOM.getChildElementByTagName(propertyElement, "f");
        var tfElements = WebCore.DOM.getChildElementsByTagName(element, "tf");
        var typefaces = null;
        if (tfElements.length > 0) {
            typefaces = new Array(tfElements.length);
            for (var i = 0; i < tfElements.length; ++i) {
                typefaces[i] = tfElements[i].getAttribute("n");
            }
        }
        
        var size = element.getAttribute("sz") ? new EchoApp.Extent(element.getAttribute("sz")) : null;
    
        var style = 0;
        if (element.getAttribute("bo")) { style |= EchoApp.Font.BOLD         };
        if (element.getAttribute("it")) { style |= EchoApp.Font.ITALIC       };
        if (element.getAttribute("un")) { style |= EchoApp.Font.UNDERLINE    };
        if (element.getAttribute("ov")) { style |= EchoApp.Font.OVERLINE     };
        if (element.getAttribute("lt")) { style |= EchoApp.Font.LINE_THROUGH };
        
        return new EchoApp.Font(typefaces, style, size);
    }
};

EchoSerial.addPropertyTranslator("Font", EchoSerial.PropertyTranslator.Font);

/**
 * ImageReference PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.ImageReference = {

    toProperty: function(client, propertyElement) {
        var url = propertyElement.getAttribute("v");
        if (client.decompressUrl) {
            url = client.decompressUrl(url);
        }
        var width = propertyElement.getAttribute("w");
        width = width ? new EchoApp.Extent(width) : null;
        var height = propertyElement.getAttribute("h");
        height = height ? new EchoApp.Extent(height) : null;
        
        return new EchoApp.ImageReference(url, width, height);
    }
};

EchoSerial.addPropertyTranslator("ImageReference", EchoSerial.PropertyTranslator.ImageReference);

/**
 * Insets PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.Insets = {

    toProperty: function(client, propertyElement) {
        return new EchoApp.Insets(propertyElement.getAttribute("v"));
    }
};

EchoSerial.addPropertyTranslator("Insets", EchoSerial.PropertyTranslator.Insets);

/**
 * LayoutData PropertyTranslator Singleton.
 */
EchoSerial.PropertyTranslator.LayoutData = {

    toProperty: function(client, propertyElement) {
        var styleData = {};
        var layoutData = new EchoApp.LayoutData(styleData);
        var element = propertyElement.firstChild;
        while (element) {
            if (element.nodeType == 1) {
                switch (element.nodeName) {
                case "p":
                    EchoSerial.loadProperty(client, element, layoutData, styleData);
                    break;
                }
            }
            element = element.nextSibling;
        }
        return layoutData;
    }
};

EchoSerial.addPropertyTranslator("LayoutData", EchoSerial.PropertyTranslator.LayoutData);
