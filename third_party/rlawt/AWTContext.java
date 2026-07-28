/*
 * Copyright (c) 2022 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rlawt;

import java.awt.Component;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Native;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class AWTContext
{
	private static boolean nativesLoaded = false;

	@Native
	private long instance;

	private Component component;
	private ComponentListener resizeListener;

	public synchronized static void loadNatives()
	{
		log("loadNatives enter nativesLoaded=" + nativesLoaded
			+ " os=" + System.getProperty("os.name")
			+ " arch=" + System.getProperty("os.arch")
			+ " direct=" + System.getenv("RLT_DIRECT_SURFACE")
			+ " java.library.path=" + System.getProperty("java.library.path")
			+ " LD_LIBRARY_PATH=" + System.getenv("LD_LIBRARY_PATH"));
		if (nativesLoaded)
		{
			log("loadNatives already loaded");
			return;
		}

		// this needs to be in core since doing this in hub plugins will break core gpu
		try
		{
			log("loading jawt via System.loadLibrary");
			System.loadLibrary("jawt");
			log("loaded jawt");
		}
		catch (UnsatisfiedLinkError e)
		{
			log("failed loading jawt: " + e.getMessage());
			throw e;
		}
		preloadAndroidEglForDirectSurface();

		String overridePath = System.getProperty("runelite.rlawtpath");
		if (overridePath != null)
		{
			try
			{
				log("loading override rlawt path=" + overridePath);
				System.load(overridePath);
				log("loaded override rlawt path=" + overridePath);
			}
			catch (UnsatisfiedLinkError e)
			{
				log("failed loading override rlawt path=" + overridePath + ": " + e.getMessage());
				throw e;
			}
			nativesLoaded = true;
			return;
		}

		String os = System.getProperty("os.name", "no-os").toLowerCase();
		String arch = System.getProperty("os.arch", "no-arch");
		String name = "unknown";
		if (os.contains("mac") || os.contains("darwin"))
		{
			os = "macos";
			name = "librlawt.dylib";
		}
		else if (os.contains("win"))
		{
			os = "windows";
			name = "rlawt.dll";
		}
		else if (os.contains("nux"))
		{
			os = "linux";
			name = "librlawt.so";
		}

		String path = os + "-" + arch + "/" + name;
		log("resolved bundled rlawt resource path=" + path);
		try (InputStream is = AWTContext.class.getResourceAsStream(path))
		{
			if (is == null)
			{
				throw new RuntimeException("rlawt does not exist at " + path);
			}

			Path temp = Files.createTempFile("", name);
			temp.toFile().deleteOnExit();
			Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
			String tempPath = temp.toAbsolutePath().toString();
			long tempSize = Files.size(temp);
			log("loading extracted rlawt path=" + tempPath + " size=" + tempSize);
			try
			{
				System.load(tempPath);
				log("loaded extracted rlawt path=" + tempPath);
			}
			catch (UnsatisfiedLinkError e)
			{
				log("failed loading extracted rlawt path=" + tempPath + ": " + e.getMessage());
				throw e;
			}
			nativesLoaded = true;
			log("loadNatives complete");
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private static void preloadAndroidEglForDirectSurface()
	{
		if (!"1".equals(System.getenv("RLT_DIRECT_SURFACE")))
		{
			return;
		}

		String arch = System.getProperty("os.arch", "");
		String libDir = arch.contains("64") || arch.contains("aarch64")
			? "/system/lib64/"
			: "/system/lib/";
		log("direct preload begin libDir=" + libDir
			+ " LD_LIBRARY_PATH=" + System.getenv("LD_LIBRARY_PATH"));
		for (String lib : new String[] { "liblzma.so", "libunwindstack.so", "libEGL.so", "libGLESv3.so" })
		{
			String path = libDir + lib;
			try
			{
				log("preloading " + path);
				System.load(path);
				log("preloaded " + path);
			}
			catch (UnsatisfiedLinkError e)
			{
				log("preload failed " + path + ": " + e.getMessage());
			}
		}
	}

	private static native long create0(Component component);

	private static void log(String msg)
	{
		System.err.println("[rlawt-java] " + msg);
		System.err.flush();
	}

	public AWTContext(Component component)
	{
		this.component = component;
		log("AWTContext ctor component=" + component
			+ " thread=" + Thread.currentThread().getName()
			+ " size=" + (component != null ? (component.getWidth() + "x" + component.getHeight()) : "null"));
		this.instance = create0(component);
		if (instance == 0)
		{
			log("AWTContext ctor: create0 returned 0 — throwing NPE");
			throw new NullPointerException();
		}
		log("AWTContext ctor: create0 returned instance=0x" + Long.toHexString(instance));

		// JAWT on osx does not set our bounds when rlawt creates the CALayer
		// so we have to calculate it's offset from the superlayer until it is first
		// resized. We assume that the superlayer is the Window
		int x = 0;
		int y = 0;
		for (Component c = component.getParent(); c != null; c = c.getParent())
		{
			if (c instanceof Window)
			{
				Insets insets = ((Window) c).getInsets();
				x -= insets.left;
				y -= insets.top;
				break;
			}

			x += c.getX();
			y += c.getY();
		}
		configureInsets(x, y);

		// Direct-Android-surface backend: forward AWT canvas resize events to
		// the native side so the AHB pool gets re-allocated at the new size.
		// On glibc/X11, notifyResized is a no-op stub at the native level
		// (X server handles ConfigureNotify), but registering the listener
		// universally keeps the Java path identical across platforms.
		this.resizeListener = new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent e)
			{
				if (instance == 0)
				{
					log("componentResized after destroy — ignoring");
					return;
				}
				int w = e.getComponent().getWidth();
				int h = e.getComponent().getHeight();
				log("componentResized w=" + w + " h=" + h
					+ " thread=" + Thread.currentThread().getName());
				if (w > 0 && h > 0)
				{
					try
					{
						notifyResized(w, h);
					}
					catch (Throwable t)
					{
						log("notifyResized threw " + t);
					}
				}
			}
		};
		component.addComponentListener(this.resizeListener);
		log("AWTContext ctor: component listener registered");
	}

	public void destroy()
	{
		log("destroy() instance=0x" + Long.toHexString(instance)
			+ " thread=" + Thread.currentThread().getName());
		if (this.component != null && this.resizeListener != null)
		{
			this.component.removeComponentListener(this.resizeListener);
			this.resizeListener = null;
			this.component = null;
		}
		destroy0();
		log("destroy() done");
	}

	private native void destroy0();

	private native void notifyResized(int width, int height);

	private native void configureInsets(int x, int y);

	public native void configurePixelFormat(int alpha, int depth, int stencil);

	public native void configureMultisamples(int samples);

	/**
	 * Gets the name of the active front or back framebuffer object.
	 */
	public native int getFramebuffer(boolean front);

	/**
	 * Gets the framebuffer target name associated with {@link #getFramebuffer(boolean)}
	 */
	public int getBufferMode()
	{
		final int GL_FRONT = 0x404;
		final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

		return getFramebuffer(true) == 0 ? GL_FRONT : GL_COLOR_ATTACHMENT0;
	}

	/**
	 * Ends the configuration phase of the context, actually creating an OpenGL
	 * context.
	 */
	public native void createGLContext();

	public native int setSwapInterval(int interval);

	public native void makeCurrent();

	public native void detachCurrent();

	/**
	 * Presents the framebuffer to the user. After calling this you MUST bind
	 * the current active framebuffer (see {@link #getFramebuffer(boolean)}) before drawing anything else
	 */
	public native void swapBuffers();

	public native long getGLContext();

	public native long getCGLShareGroup();

	public native long getGLXDisplay();

	public native long getWGLHDC();
}
