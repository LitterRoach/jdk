/*
 * Copyright 2006-2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package sun.module.core;

import java.io.*;
import java.module.*;
import java.module.annotation.ClassesDirectoryPath;
import java.nio.channels.Channels;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.AccessControlContext;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.PermissionCollection;
import java.security.ProtectionDomain;
import java.security.SecureClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;


/**
 * Class loader implementation for the module system.
 *
 * @since 1.7
 */
final class ModuleLoader extends SecureClassLoader {

    final static boolean DEBUG = ModuleImpl.DEBUG;

    private final ModuleDefinition moduleDef;
    private final ModuleContent content;
    private final CodeSource cs;

    private Module module = null;
    private List<Module> importedModules;
    private Manifest manifest = null;
    private boolean manifestSet = false;
    private String classesDirectoryPath = null;

    ModuleLoader(ModuleDefinition moduleDef, final ModuleContent content, CodeSource cs) {
        super();
        this.moduleDef = moduleDef;
        this.content = content;
        this.cs = cs;

        try {
            Module coreModule = moduleDef.getRepository().find("java.se.core").getModuleInstance();
            importedModules = Collections.singletonList(coreModule);
        } catch (ModuleInitializationException e) {
            // no-op
        }

        ClassesDirectoryPath classesDirectoryPathAnnotation = moduleDef.getAnnotation(ClassesDirectoryPath.class);
        if (classesDirectoryPathAnnotation != null) {
            classesDirectoryPath = classesDirectoryPathAnnotation.value();
            if (classesDirectoryPath.startsWith("/"))  {
                System.err.println("Path in @ClassesDirectoryPath must not start with '/'.");
            }
            if (classesDirectoryPath.endsWith("/")) {
                // TODO: use logging
                System.err.println("Warning: Path in @ClassesDirectoryPath should not have trailing '/'; ignore trailing '/'.");
                classesDirectoryPath = classesDirectoryPath.substring(0, classesDirectoryPath.length() - 1);
            }
        }
    }

    @Override
    public Module getModule() {
        if (module == null) {
            throw new IllegalStateException("Module instance has not been fully initialized.");
        }
        return module;
    }

    void setModule(Module module, List<Module> importedModules) {
        this.module = module;
        this.importedModules = importedModules;
    }

    private AccessControlContext restrictedAccessControlContext = null;

    /**
     * Returns a restricted access control context which has the
     * same permissions as the classes loaded by this module class
     * loader.
     */
    AccessControlContext getRestrictedAccessControlContext()  {
        if (restrictedAccessControlContext == null) {
            // Set up a virtual protection domain exactly as the protection
            // domain of the class loaded by the module class loader.
            ProtectionDomain[] domains = new ProtectionDomain[1];
            PermissionCollection perms = getPermissions(cs);
            domains[0] = new ProtectionDomain(cs, perms, this, null);

            restrictedAccessControlContext = new AccessControlContext(domains);
        }
        return restrictedAccessControlContext;
    }

    /**
     * Module ClassLoader main method.
     * Note that we delegate directly to the correct ClassLoader based on
     * ModuleDefinition export information. This means that during normal operation
     * we do not encounter ClassNotFoundExceptions. That is different from
     * traditional ClassLoading where each delegation results in a
     * ClassNotFoundException.
     */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException
    {
        // First, check if the class has already been loaded
        Class<?> c = findLoadedClass(name);

        if (c == null) {
            // try to load the classes from the imported modules
            for (Module importedModule : importedModules) {
                // if the module exports the given class, then delegate to it
                if (importedModule.getModuleDefinition().isClassExported(name)) {
                    try {
                        ClassLoader importedLoader = importedModule.getClassLoader();
                        c = importedLoader.loadClass(name);
                        break;
                    } catch (ClassNotFoundException e) {
                        // should not occur
                        if (DEBUG) System.out.println("-IMP: " + e);
                    }
                }
            }
        }

        if (c == null) {
            // check myself
            c = findClass(name);
        }

        // we do not delegate to the parent

        if (resolve) {
            resolveClass(c);
        }
        return c;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        if (classesDirectoryPath != null) {
            path = classesDirectoryPath + '/' + path;
        }

        try {
            if (content.hasEntry(path) == false) {
                throw new ClassNotFoundException(name);
            }

            // module's code source URL is the sealed URL
            URL sealBase = cs.getLocation();

            // defines package
            int i = name.lastIndexOf('.');
            if (i != -1) {
                String pkgname = name.substring(0, i);
                // checks if package already loaded.
                Package pkg = getPackage(pkgname);
                if (pkg != null) {
                    // package found, so check package sealing.
                    if (pkg.isSealed()) {
                        // verifies that code source URL is the same.
                        if (!pkg.isSealed(sealBase)) {
                            throw new SecurityException(
                                "sealing violation: package " + pkgname + " is not sealed with " + sealBase);
                        }

                    } else {
                        // all packages defined in a module definition are
                        // inheritly sealed
                        throw new SecurityException(
                            "sealing violation: package " + pkgname +
                            " is already loaded but is not sealed");
                    }
                } else {
                    Manifest man = getManifest();
                    if (man != null) {
                        definePackage(pkgname, man, sealBase);
                    } else {
                        definePackage(pkgname, null, null, null, null, null, null, sealBase);
                    }
                }
            }

            java.nio.ByteBuffer classData = content.getEntryAsByteBuffer(path);
            return defineClass(name, classData, cs);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    /**
     * Defines a new package by name in this ClassLoader. The attributes
     * contained in the specified Manifest will be used to obtain package
     * version and sealing information. For sealed packages, the additional
     * URL specifies the code source URL from which the package was loaded.
     *
     * @param name  the package name
     * @param man   the Manifest containing package version and sealing
     *              information
     * @param url   the code source url for the package, or null if none
     * @exception   IllegalArgumentException if the package name duplicates
     *              an existing package either in this class loader or one
     *              of its ancestors
     * @return the newly defined Package object
     */
    private Package definePackage(String name, Manifest man, URL url)
        throws IllegalArgumentException
    {
        String path = name.replace('.', '/').concat("/");
        String specTitle = null, specVersion = null, specVendor = null;
        String implTitle = null, implVersion = null, implVendor = null;

        Attributes attr = man.getAttributes(path);
        if (attr != null) {
            specTitle   = attr.getValue(Name.SPECIFICATION_TITLE);
            specVersion = attr.getValue(Name.SPECIFICATION_VERSION);
            specVendor  = attr.getValue(Name.SPECIFICATION_VENDOR);
            implTitle   = attr.getValue(Name.IMPLEMENTATION_TITLE);
            implVersion = attr.getValue(Name.IMPLEMENTATION_VERSION);
            implVendor  = attr.getValue(Name.IMPLEMENTATION_VENDOR);
        }
        attr = man.getMainAttributes();
        if (attr != null) {
            if (specTitle == null) {
                specTitle = attr.getValue(Name.SPECIFICATION_TITLE);
            }
            if (specVersion == null) {
                specVersion = attr.getValue(Name.SPECIFICATION_VERSION);
            }
            if (specVendor == null) {
                specVendor = attr.getValue(Name.SPECIFICATION_VENDOR);
            }
            if (implTitle == null) {
                implTitle = attr.getValue(Name.IMPLEMENTATION_TITLE);
            }
            if (implVersion == null) {
                implVersion = attr.getValue(Name.IMPLEMENTATION_VERSION);
            }
            if (implVendor == null) {
                implVendor = attr.getValue(Name.IMPLEMENTATION_VENDOR);
            }
        }
        return definePackage(name, specTitle, specVersion, specVendor,
                             implTitle, implVersion, implVendor, url);
    }

    /**
     * Returns the Manifest in the module, or null if none.
     */
    private Manifest getManifest() throws IOException {
        if (manifestSet) {
            return manifest;
        }
        if (content.hasEntry(JarFile.MANIFEST_NAME)) {
            InputStream is = Channels.newInputStream(content.getEntryAsChannel(JarFile.MANIFEST_NAME));
            manifest = new Manifest(is);
        }
        manifestSet = true;
        return manifest;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        for (Module importedModule : importedModules) {
            if (importedModule.getModuleDefinition().isResourceExported(name)) {
                InputStream in = importedModule.getClassLoader().getResourceAsStream(name);
                if (in != null) {
                    return in;
                }
            }
        }
        String path = name;
        if (classesDirectoryPath != null) {
            path = classesDirectoryPath + '/' + name;
        }
        try {
            if (content.hasEntry(path)) {
                return Channels.newInputStream(content.getEntryAsChannel(path));
            }
        } catch (IOException e) {
            // fallback to next return
        }
        return null;
    }

    @Override
    public URL getResource(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        for (Module importedModule : importedModules) {
            if (importedModule.getModuleDefinition().isResourceExported(name)) {
                URL u = importedModule.getClassLoader().getResource(name);
                if (u != null) {
                    return u;
                }
            }
        }
        return findResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (name == null) {
            throw new NullPointerException();
        }
        Vector<URL> v = new Vector<URL>();
        for (Module importedModule : importedModules) {
            if (importedModule.getModuleDefinition().isResourceExported(name)) {
                for (URL url : Collections.list(importedModule.getClassLoader().getResources(name)))  {
                    v.add(url);
                }
            }
        }
        URL u = findResource(name);
        if (u != null) {
            v.add(u);
        }
        return v.elements();
    }

    @Override
    protected URL findResource(String name) {
        String path = name;
        if (classesDirectoryPath != null) {
            path = classesDirectoryPath + '/' + name;
        }
        try {
            if (content.hasEntry(path) == false) {
                return null;
            }
            ResourceHandler handler = new ResourceHandler(this, path);
            URL url = new URL("x-module-internal",
                moduleDef.getName() + "-" + moduleDef.getVersion(),
                -1, "/" + path, handler);
            handler.url = url;
            return url;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected Enumeration<URL> findResources(String name) throws IOException {
        Vector<URL> v = new Vector<URL>();
        URL url = findResource(name);
        if (url != null) {
            v.add(url);
        }
        return v.elements();
    }

    private static class ResourceHandler extends URLStreamHandler {

        final ModuleLoader loader;
        final String name;
        URL url;

        ResourceHandler(ModuleLoader loader, String name) {
            this.loader = loader;
            this.name = name;
        }

        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            if (this.url != u) {
                throw new IOException("Invalid URL for this handler: " + u);
            }
            return new ResourceConnection(url, loader, name);
        }

    }

    private static class ResourceConnection extends URLConnection {

        private final String name;
        private final InputStream in;
        private volatile Map<String,List<String>> headerFields;

        ResourceConnection(URL url, ModuleLoader loader, String name) throws IOException {
            super(url);
            this.name =  name;
            in = Channels.newInputStream(loader.content.getEntryAsChannel(name));
            if (in == null) {
                throw new IOException("Content no longer available: " + name);
            }
        }

        @Override
        public void connect() throws IOException {
            // empty
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return in;
        }

        @Override
        public String getHeaderField(String name) {
            List<String> v = getHeaderFields().get(name.toLowerCase());
            return (v == null) ? null : v.get(v.size() - 1);
        }

        @Override
        public Map<String,List<String>> getHeaderFields() {
            if (headerFields == null) {
                String type = guessContentTypeFromName(name);
                if (type == null) {
                    type = "application/octet-stream";
                }
                headerFields = Collections.singletonMap("content-type",
                    Collections.singletonList(type));
            }
            return headerFields;
        }
    }

    @Override
    protected String findLibrary(String libname) {
        try {
            File lib = content.getNativeLibrary(libname);
            if (lib != null) {
                return lib.getCanonicalPath();
            }
        } catch (IOException ex) {
            // ignore exception
        }
        return null;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ModuleClassLoader[module=");
        builder.append(moduleDef.getName());
        builder.append(" v");
        builder.append(moduleDef.getVersion());
        builder.append(", imported-modules=[");
        boolean first = true;
        for (Module m : importedModules) {
            if (!first) {
                builder.append(", ");
            }
            ModuleDefinition md = m.getModuleDefinition();
            builder.append(md.getName());
            builder.append(" v");
            builder.append(md.getVersion());
            first = false;
        }
        builder.append("]]");
        return builder.toString();
    }
}
