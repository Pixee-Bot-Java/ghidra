/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.file.formats.android.dex;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessMode;
import java.security.SecureRandom;
import java.util.*;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;

import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.bin.FileByteProvider;
import ghidra.file.formats.android.dex.format.DexConstants;
import ghidra.formats.gfilesystem.*;
import ghidra.formats.gfilesystem.annotations.FileSystemInfo;
import ghidra.formats.gfilesystem.factory.GFileSystemBaseFactory;
import ghidra.framework.Application;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.CryptoException;
import ghidra.util.task.TaskMonitor;

@FileSystemInfo(type = "dex2smali", description = "Android DEX to SMALI", factory = GFileSystemBaseFactory.class)
public class DexToSmaliFileSystem extends GFileSystemBase {

	private Map<GFile, File> map = new HashMap<>();

	public DexToSmaliFileSystem(String fileSystemName, ByteProvider provider) {
		super(fileSystemName, provider);
	}

	@Override
	public ByteProvider getByteProvider(GFile file, TaskMonitor monitor)
			throws IOException, CancelledException {
		File entry = map.get(file);
		return new FileByteProvider(entry, file.getFSRL(), AccessMode.READ);
	}

	@Override
	public List<GFile> getListing(GFile directory) throws IOException {

		if (directory == null || directory.equals(root)) {
			List<GFile> roots = new ArrayList<>();
			for (GFile file : map.keySet()) {
				if (file.getParentFile() == root || file.getParentFile().equals(root)) {
					roots.add(file);
				}
			}
			return roots;
		}
		List<GFile> tmp = new ArrayList<>();
		for (GFile file : map.keySet()) {
			if (file.getParentFile() == null) {
				continue;
			}
			if (file.getParentFile().equals(directory)) {
				tmp.add(file);
			}
		}
		return tmp;
	}

	@Override
	public boolean isValid(TaskMonitor monitor) throws IOException {
		return DexConstants.isDexFile(provider);
	}

	//Baksmali
	@Override
	public void open(TaskMonitor monitor) throws IOException, CryptoException, CancelledException {
		monitor.setMessage("Converting DEX to SMALI...");

		int rand = new SecureRandom().nextInt() & 0xffff;
		File outputDir = new File(Application.getUserTempDirectory(), "ghidra_file_system_" + rand);

		DexFile dexFile =
			DexBackedDexFile.fromInputStream(Opcodes.getDefault(), provider.getInputStream(0));

		BaksmaliOptions options = new BaksmaliOptions();
		options.apiLevel = 15;
		options.parameterRegisters = true;
		options.localsDirective = false;
		options.sequentialLabels = false;
		options.debugInfo = true;
		options.codeOffsets = false;
		options.accessorComments = true;
		options.allowOdex = false;
		options.deodex = false;
		options.implicitReferences = false;
		options.normalizeVirtualMethods = false;
		options.registerInfo = 0;

		if (!Baksmali.disassembleDexFile(dexFile, outputDir, 1, options)) {
			throw new IOException("Failed to disassemble DEX file: " + provider.getName());
		}

		getFileListing(outputDir, root, monitor);
	}

	private void getFileListing(File startingDirectory, GFileImpl currentRoot,
			TaskMonitor monitor) {

		Iterator<File> iterator = FileUtils.iterateFiles(startingDirectory, TrueFileFilter.INSTANCE,
			TrueFileFilter.INSTANCE);

		while (iterator.hasNext()) {
			File f = iterator.next();

			if (monitor.isCancelled()) {
				break;
			}
			monitor.setMessage(f.getName());

			GFileImpl gfile = GFileImpl.fromFilename(this, currentRoot, f.getName(),
				f.isDirectory(), f.length(), null);
			storeFile(gfile, f);
		}
	}

	private void storeFile(GFile file, File entry) {
		if (file == null) {
			return;
		}
		if (file.equals(root)) {
			return;
		}
		if (!map.containsKey(file) || map.get(file) == null) {
			map.put(file, entry);
		}
		GFile parentFile = file.getParentFile();
		storeFile(parentFile, null);
	}

	@Override
	public void close() throws IOException {
		map.clear();
		super.close();
	}
}

/*	DexHeader header = new DexHeader(provider);
if (!header.getMagic().equals(DexConstants.MAGIC)) {
	throw new IOException("Unable to open file: invalid DEX file!");
}
BinaryReader reader = new BinaryReader(provider, true);
int stringTableOffset = header.getStringTableOffset();
String classNameArr[] = new String[header.getClassTableSize()];
long last = 0;
int stringAddress;
GFile file;
DexClass myClass;

for(int i = 0; i < header.getClassTableSize(); i++){
	if(i == 0){
		//Get offset of Class Name in String Table
		last = header.getClassTableOffset();
		//Reads from String Table to get address of class Name
		stringAddress = reader.readInt((reader.readInt(last+16))*4 + stringTableOffset);
		//Reads String at String Address and stores in Array
		classNameArr[i] = reader.readAsciiString(stringAddress+1);
		monitor.setMessage(classNameArr[i]);
		file = new GFile(this, root, classNameArr[i], false, header.getClassTableSize());
		myClass = new DexClass(reader.readInt(last), reader.readInt(last+4), reader.readInt(last+8), reader.readInt(last+12), reader.readInt(last+16), reader.readInt(last+20), reader.readInt(last+24), reader.readInt(last+28));
		storeFile(file, myClass);
	}else{
		last = last + 32;
		stringAddress = reader.readInt((reader.readInt(last+16))*4 + stringTableOffset);
		classNameArr[i] = reader.readAsciiString(stringAddress+1);
		monitor.setMessage(classNameArr[i]);
		file = new GFile(this, root, classNameArr[i], false, header.getClassTableSize());
		myClass = new DexClass(reader.readInt(last), reader.readInt(last+4), reader.readInt(last+8), reader.readInt(last+12), reader.readInt(last+16), reader.readInt(last+20), reader.readInt(last+24), reader.readInt(last+28));
		storeFile(file, myClass);
	}
}
/*
int typeTableOffset = header.getTypeTableOffset();
int classTypeOffset = 0x2d * 4 + typeTableOffset;
int classNameStringID = 0;
 */
