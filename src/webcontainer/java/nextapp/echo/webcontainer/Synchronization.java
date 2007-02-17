package nextapp.echo.webcontainer;

import java.io.IOException;
import java.util.Iterator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import nextapp.echo.app.ApplicationInstance;
import nextapp.echo.app.Component;
import nextapp.echo.app.ContentPane;
import nextapp.echo.app.Style;
import nextapp.echo.app.StyleSheet;
import nextapp.echo.app.Window;
import nextapp.echo.app.update.ClientUpdateManager;
import nextapp.echo.app.update.ServerComponentUpdate;
import nextapp.echo.app.update.ServerUpdateManager;
import nextapp.echo.app.update.UpdateManager;
import nextapp.echo.app.util.DomUtil;
import nextapp.echo.app.xml.XmlPropertyPeer;

//FIXME. Move low-level XML ServerMessage work out of ServerMessage and into ServerMeessageDOM or something like that.
//FIXME. Move servermessage rendering code into new ServerMessage object that has appropriate API

/**
 * A single client-server synchronziation.
 */
public class Synchronization {

    private static final String[] PROPERTIES_LAYOUT_DATA = new String[]{Component.PROPERTY_LAYOUT_DATA};
    
    private class InputContextImpl implements InputContext {

        
        private InputContextImpl() 
        throws IOException {
            super();
        }
        
        public ClientUpdateManager getClientUpdateManager() {
            return conn.getUserInstance().getApplicationInstance().getUpdateManager().getClientUpdateManager();
        }
        
        /**
         * @see nextapp.echo.webcontainer.InputContext#getClientMessage()
         */
        public ClientMessage getClientMessage() {
            return clientMessage;
        }

        /**
         * @see nextapp.echo.webcontainer.InputContext#getConnection()
         */
        public Connection getConnection() {
            return conn;
        }

        /**
         * @see nextapp.echo.webcontainer.InputContext#getUserInstance()
         */
        public UserInstance getUserInstance() {
            return conn.getUserInstance();
        }

        /**
         * @see nextapp.echo.app.xml.XmlContext#getClassLoader()
         */
        public ClassLoader getClassLoader() {
            //FIXME. temporary, not what we want.
            return Thread.currentThread().getContextClassLoader();
        }

        /**
         * @see nextapp.echo.app.xml.XmlContext#getDocument()
         */
        public Document getDocument() {
            return clientMessage.getDocument();
        }

        /**
         * @see nextapp.echo.app.xml.XmlContext#getPropertyPeer(java.lang.Class)
         */
        public XmlPropertyPeer getPropertyPeer(Class propertyClass) {
            return null;
        }
    }
    
    private class OutputContextImpl implements OutputContext {

        private ServerMessage serverMessage = new ServerMessage();
        
        /**
         * @see nextapp.echo.webcontainer.OutputContext#getConnection()
         */
        public Connection getConnection() {
            return conn;
        }

        /**
         * @see nextapp.echo.webcontainer.OutputContext#getServerMessage()
         */
        public ServerMessage getServerMessage() {
            return serverMessage;
        }

        /**
         * @see nextapp.echo.webcontainer.OutputContext#getUserInstance()
         */
        public UserInstance getUserInstance() {
            return conn.getUserInstance();
        }

        /**
         * @see nextapp.echo.app.xml.XmlContext#getClassLoader()
         */
        public ClassLoader getClassLoader() {
            //FIXME. temporary, not what we want.
            return Thread.currentThread().getContextClassLoader();
        }

        /**
         * @see nextapp.echo.app.xml.XmlContext#getDocument()
         */
        public Document getDocument() {
            return serverMessage.getDocument();
        }

        public XmlPropertyPeer getPropertyPeer(Class propertyClass) {
            // TODO Auto-generated method stub
            return null;
        }
    }    

    private ClientMessage clientMessage;
    private ServerMessage serverMessage;
    private Connection conn;

    public Synchronization(Connection conn) {
        super();
        this.conn = conn;
    }
    
    private Class getStyleClass(StyleSheet styleSheet, String styleName, Class componentClass) {
        if (styleSheet.getStyle(styleName, componentClass, false) != null) {
            // StyleSheet provides style specifically for componentClass.
            return componentClass;
        }
        
        // StyleSheet does not provide style specifically for componentClass: search superclasses.
        componentClass = componentClass.getSuperclass();
        while (componentClass != null) {
            if (styleSheet.getStyle(styleName, componentClass, false) != null) {
                return componentClass;
            }
            componentClass = componentClass.getSuperclass();
        }
        
        return null;
    }
    
    public void process() 
    throws IOException {
        final UserInstance userInstance = conn.getUserInstance();
        
        synchronized(userInstance) {
            boolean initRequired = !userInstance.isInitialized();
            
            if (initRequired) {
                // Initialize user instance.
                userInstance.init(conn);
            }

            ApplicationInstance.setActive(userInstance.getApplicationInstance());
            try {
                if (!initRequired) {
                    // Process client input.
                    //FIXME. there is chicken-egg stinkiness going on here:
                    InputContext inputContext = new InputContextImpl();
                    clientMessage = new ClientMessage(inputContext);
                    
                    processClientInput(inputContext);
                    userInstance.getApplicationInstance().getUpdateManager().processClientUpdates();
                }
                
                // Render updates.
                serverMessage = new ServerMessage();
                serverMessage.setTransactionId(conn.getUserInstance().getNextTransactionId());
                
                OutputContext outputContext = new OutputContextImpl();
                processServerOutput(outputContext);
                conn.setContentType(ContentType.TEXT_XML);
                outputContext.getServerMessage().render(conn.getWriter());
            } finally {
                ApplicationInstance.setActive(null);
            }
        }
    }

    private void processClientInput(InputContext context) {
        UserInstance userInstance = context.getUserInstance();
        UpdateManager updateManager = userInstance.getUpdateManager();
        ClientUpdateManager clientUpdateManager = updateManager.getClientUpdateManager();
        ClientMessage clientMessage = context.getClientMessage();
        
        if (ClientMessage.TYPE_INITIALIZE.equals(clientMessage.getType())) {
            // Flag full refresh if initializing.
            updateManager.getServerUpdateManager().processFullRefresh();
        }
        
        Iterator updatedComponentIt  = clientMessage.getUpdatedComponents();
        while (updatedComponentIt.hasNext()) {
            Component component = (Component) updatedComponentIt.next();
            Iterator updatedPropertyIt = clientMessage.getUpdatedPropertyNames(component);
            while (updatedPropertyIt.hasNext()) {
                String propertyName = (String) updatedPropertyIt.next();
                Object propertyValue = clientMessage.getUpdatedPropertyValue(component, propertyName);
                ComponentSynchronizePeer componentPeer = SynchronizePeerFactory.getPeerForComponent(component.getClass());
                componentPeer.storeInputProperty(context, component, propertyName, propertyValue);
            }
        }
        
        //FIXME. process clientmessage properties.
        
        if (clientMessage.getEventType() != null) {
            Component component = userInstance.getComponentByElementId(clientMessage.getEventComponentId());
            clientUpdateManager.setComponentAction(component, clientMessage.getEventType(), null);
        }
    }

    private void processServerOutput(OutputContext context) {
        UserInstance userInstance = context.getUserInstance();
        UpdateManager updateManager = userInstance.getUpdateManager();
        ServerUpdateManager serverUpdateManager = updateManager.getServerUpdateManager();
        
        ServerMessage serverMessage = context.getServerMessage();
        
        if (serverUpdateManager.isFullRefreshRequired()) {
            renderStyleSheet(context);
            ContentPane content = userInstance.getApplicationInstance().getDefaultWindow().getContent();
            if (content == null) {
                throw new IllegalStateException("No content to render: default window has no content.");
            }
            Element addElement = serverMessage.addDirective(ServerMessage.GROUP_ID_UPDATE, "CSync", "add");
            addElement.setAttribute("i", "c_root");
            renderComponentState(context, addElement, content);
        } else {
            ServerComponentUpdate[] componentUpdates = updateManager.getServerUpdateManager().getComponentUpdates();
            for (int i = 0; i < componentUpdates.length; ++i) {
                // Removed children.
                Component[] removedChildren = componentUpdates[i].getRemovedChildren();
                for (int j = 0; j < removedChildren.length; ++j) {
                    Element rmElement = serverMessage.addDirective(ServerMessage.GROUP_ID_UPDATE, "CSync", "rm");
                    rmElement.setAttribute("i", UserInstance.getElementId(removedChildren[j]));
                }
                
                // Added children.
                Component[] addedChildren = componentUpdates[i].getAddedChildren();
                if (addedChildren.length > 0) {
                    Element addElement = serverMessage.addDirective(ServerMessage.GROUP_ID_UPDATE, "CSync", "add");
                    String parentId;
                    //FIXME. Ugly hack for root window id.  Need to either render window as a div...or something.
                    if (componentUpdates[i].getParent() instanceof Window) {
                        parentId = "c_root";
                    } else {
                        parentId = UserInstance.getElementId(componentUpdates[i].getParent());
                    }
                    addElement.setAttribute("i", parentId);
                    for (int j = 0; j < addedChildren.length; ++j) {
                        Element cElement = renderComponentState(context, addElement, addedChildren[j]);
                        cElement.setAttribute("x", 
                                Integer.toString(componentUpdates[i].getParent().indexOf(addedChildren[j])));
                    }
                }
                
                // Updated properties.
                //FIXME. move to method?
                String[] updatedPropertyNames = componentUpdates[i].getUpdatedPropertyNames();
                if (updatedPropertyNames.length > 0) {
                    Element upElement = serverMessage.addDirective(ServerMessage.GROUP_ID_UPDATE, "CSync", "up");
                    upElement.setAttribute("i", UserInstance.getElementId(componentUpdates[i].getParent()));
                    renderUpdatedProperties(context, upElement, componentUpdates[i].getParent(), updatedPropertyNames);
                }
                
                Component[] updatedLayoutDataChildren = componentUpdates[i].getUpdatedLayoutDataChildren();
                for (int j = 0; j < updatedLayoutDataChildren.length; ++j) {
                    Element upElement = serverMessage.addDirective(ServerMessage.GROUP_ID_UPDATE, "CSync", "up");
                    upElement.setAttribute("i", UserInstance.getElementId(updatedLayoutDataChildren[j]));
                    renderUpdatedProperties(context, upElement, updatedLayoutDataChildren[j], PROPERTIES_LAYOUT_DATA);
                }
            }
        }
        
        updateManager.purge();
        
        try {
            DomUtil.save(serverMessage.getDocument(), System.err, DomUtil.OUTPUT_PROPERTIES_INDENT);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * Renders the full state of a specific component.
     * 
     * @param context 
     * @param parentElement
     * @param c
     */
    private Element renderComponentState(OutputContext context, Element parentElement, Component c) {
        Document document = parentElement.getOwnerDocument();
        ComponentSynchronizePeer componentPeer = SynchronizePeerFactory.getPeerForComponent(c.getClass());
        if (componentPeer == null) {
            throw new IllegalStateException("No synchronize peer found for component: " + c.getClass().getName());
        }
        
        Element cElement = document.createElement("c");
        cElement.setAttribute("i", UserInstance.getElementId(c));

        cElement.setAttribute("t", componentPeer.getClientComponentType());
        componentPeer.init(context);

        StyleSheet styleSheet = c.getApplicationInstance().getStyleSheet();
        
        // Render style name (and style type, if necessary). 
        if (styleSheet != null && c.getStyleName() != null) {
            cElement.setAttribute("s", c.getStyleName());
            Class styleClass = getStyleClass(styleSheet, c.getStyleName(), c.getClass());
            if (styleClass != null && styleClass != c.getClass()) {
                ComponentSynchronizePeer styleComponentSyncPeer 
                        = SynchronizePeerFactory.getPeerForComponent(styleClass, false);
                if (styleComponentSyncPeer == null) {
                    cElement.setAttribute("st", styleClass.getName());
                } else {
                    cElement.setAttribute("st", styleComponentSyncPeer.getClientComponentType());
                }
            }
        }
        
        // Render component properties.
        Iterator propertyNameIterator = componentPeer.getOutputPropertyNames(c);
        while (propertyNameIterator.hasNext()) {
            String propertyName = (String) propertyNameIterator.next();
            Object propertyValue = componentPeer.getOutputProperty(context, c, propertyName);
            PropertySynchronizePeer propertySyncPeer = SynchronizePeerFactory.getPeerForProperty(propertyValue.getClass());
            if (propertySyncPeer == null) {
                //FIXME. figure out how these should be handled...ignoring is probably best.
                System.err.println("No peer for: " + propertyValue.getClass());
                continue;
            }
            Element pElement = document.createElement("p");
            pElement.setAttribute("n", propertyName);
            propertySyncPeer.toXml(context, c.getClass(), pElement, propertyValue);
            cElement.appendChild(pElement);
        }
        
        // Render immediate event flags.
        Iterator eventTypeIterator = componentPeer.getImmediateEventTypes(c);
        while (eventTypeIterator.hasNext()) {
            String eventType = (String) eventTypeIterator.next();
            Element eElement = document.createElement("e");
            eElement.setAttribute("t", eventType);
            cElement.appendChild(eElement);
        }
        
        // Render child components.
        Component[] children = c.getVisibleComponents();
        for (int i = 0; i < children.length; ++i) {
            renderComponentState(context, cElement, children[i]);
        }
        
        // Append component element to parent.
        parentElement.appendChild(cElement);
        
        return cElement;
    }
    
    private void renderStyleSheet(OutputContext context) {
        ServerMessage serverMessage = context.getServerMessage();
        Element ssElement = serverMessage.addDirective(ServerMessage.GROUP_ID_UPDATE, "CSync", "ss");
        
        StyleSheet styleSheet = context.getUserInstance().getApplicationInstance().getStyleSheet();
        if (styleSheet == null) {
            return;
        }
        
        Document document = ssElement.getOwnerDocument();
        Iterator styleNameIterator = styleSheet.getStyleNames();
        while (styleNameIterator.hasNext()) {
            String styleName = (String) styleNameIterator.next();
            Iterator componentTypeIterator = styleSheet.getComponentTypes(styleName);
            while (componentTypeIterator.hasNext()) {
                Class componentClass = (Class) componentTypeIterator.next();
                Element sElement = document.createElement("s");
                ComponentSynchronizePeer componentPeer = SynchronizePeerFactory.getPeerForComponent(componentClass);
                if (componentPeer != null) {
                    sElement.setAttribute("t", componentPeer.getClientComponentType());
                } else {
                    sElement.setAttribute("t", componentClass.getName());
                }
                if (styleName != null) {
                    sElement.setAttribute("n", styleName);
                }
                
                Style style = styleSheet.getStyle(styleName, componentClass, false);
                renderStyle(context, componentClass, sElement, style);
                
                ssElement.appendChild(sElement);
            }
        }
    }
    
    private void renderStyle(OutputContext context, Class objectClass, Element parentElement, Style style) {
        Document document = parentElement.getOwnerDocument();
        Iterator it = style.getPropertyNames();
        while (it.hasNext()) {
            String propertyName = (String) it.next();
            Object propertyValue = style.getProperty(propertyName);
            if (propertyValue == null) {
                continue;
            }
            PropertySynchronizePeer propertySyncPeer = SynchronizePeerFactory.getPeerForProperty(propertyValue.getClass());
            if (propertySyncPeer == null) {
                //FIXME. figure out how these should be handled...ignoring is probably best.
                System.err.println("No peer for: " + propertyValue.getClass());
                continue;
            }
            Element pElement = document.createElement("p");
            pElement.setAttribute("n", propertyName);
            propertySyncPeer.toXml(context, objectClass, pElement, propertyValue);
            parentElement.appendChild(pElement);
        }
    }

    private void renderUpdatedProperties(OutputContext context, Element upElement, Component c, 
            String[] updatedPropertyNames) {
        Document document = context.getServerMessage().getDocument();
        ComponentSynchronizePeer componentPeer = SynchronizePeerFactory.getPeerForComponent(c.getClass());
        if (componentPeer == null) {
            throw new IllegalStateException("No synchronize peer found for component: " + c.getClass().getName());
        }
        
        for (int i = 0; i < updatedPropertyNames.length; ++i) {
            Element pElement = document.createElement("p");
            pElement.setAttribute("n", updatedPropertyNames[i]);
            Object propertyValue = componentPeer.getOutputProperty(context, c, updatedPropertyNames[i]);
            if (propertyValue == null) {
                pElement.setAttribute("t", "0");
                //FIXME. handle properties changed to null.
                System.err.println("NULLED: " + updatedPropertyNames[i]);
            } else {
                PropertySynchronizePeer propertySyncPeer = SynchronizePeerFactory.getPeerForProperty(
                        propertyValue.getClass());
                if (propertySyncPeer == null) {
                    //FIXME. figure out how these should be handled...ignoring is probably best.
                    System.err.println("No peer for: " + propertyValue.getClass());
                    continue;
                }
                propertySyncPeer.toXml(context, c.getClass(), pElement, propertyValue);
            }
            upElement.appendChild(pElement);
        }
    }
    
//FIXME. Re-add isRendered() method and use appropriately.    
//    /**
//     * Determines if the specified <code>component</code> has been rendered to
//     * the client by determining if it is a descendant of any
//     * <code>LazyRenderContainer</code>s and if so querying them to determine
//     * the hierarchy's render state. This method is recursively invoked.
//     * 
//     * @param userInstance the relevant <code>UserInstance</code>
//     * @param component the <code>Component</code> to analyze
//     * @return <code>true</code> if the <code>Component</code> has been
//     *         rendered to the client
//     */
//    private boolean isRendered(UserInstance userInstance, Component component) {
//        Component parent = component.getParent();
//        if (parent == null) {
//            return true;
//        }
//        ComponentSynchronizePeer syncPeer = SynchronizePeerFactory.getPeerForComponent(parent.getClass());
//        if (syncPeer instanceof LazyRenderContainer) {
//            boolean rendered = ((LazyRenderContainer) syncPeer).isRendered(ci, parent, component);
//            if (!rendered) {
//                return false;
//            }
//        }
//        return isRendered(ci, parent);
//        return true;
//    }    

}
