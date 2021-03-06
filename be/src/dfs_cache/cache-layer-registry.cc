/*
 * cache-layer-registry.cc
 *
 *  Created on: Oct 9, 2014
 *      Author: elenav
 */

#ifndef CACHE_LAYER_REGISTRY_CC_
#define CACHE_LAYER_REGISTRY_CC_

#include <boost/scoped_ptr.hpp>
#include "dfs_cache/cache-layer-registry.hpp"

namespace impala{

boost::scoped_ptr<CacheLayerRegistry> CacheLayerRegistry::instance_;
std::string CacheLayerRegistry::fileSeparator;

bool CacheLayerRegistry::init(int mem_limit_percent, const std::string& root,
		boost::posix_time::time_duration timeslice,
		int size_hard_limit) {
	// configure platform-specific file separator:
	boost::filesystem::path slash("/");
	boost::filesystem::path::string_type preferredSlash = slash.make_preferred().native();
	fileSeparator = preferredSlash;

	if(CacheLayerRegistry::instance_.get() == NULL)
		CacheLayerRegistry::instance_.reset(new CacheLayerRegistry(mem_limit_percent, root, timeslice, size_hard_limit));

  if(!CacheLayerRegistry::instance()->valid()){
  	  LOG (ERROR) << "Cache initialization is interrupted due to incorrect cache location \"" << root << "\".\n";
  	  return false;
    }

  // Initialize File class
  managed_file::File::initialize();

	// reload the cache:
  CacheLayerRegistry::instance()->reload();

  return true;
}

status::StatusInternal CacheLayerRegistry::setupFileSystem(FileSystemDescriptor & fsDescriptor){
	/* We may receive here following FileSystem configurations:
	 * 1. {"default", 0} - in this case we need to delegate the host and port resolution to the Hadoop FileSystem class
	 *    which will locate the CLASSPATH's available core-site.xml and get the FS host and port from URI defined in
	 *    <property>
  	  	  	  <name>fs.defaultFS</name>
  	  	  	  <value>hdfs://namenode_hostname:port</value>
		  </property>

	   2. {NULL, 0} - in this case local file system is constructed

	   3. {hostname, [port]} - in this case we construct the FileSystem explicitly
	 *
	 */
	if(fsDescriptor.host == constants::DEFAULT_FS){
		// run resolution scenario via hadoop filesystem:
		int status = FileSystemDescriptorBound::resolveFsAddress(fsDescriptor);
		if(status){
			LOG (ERROR) << "Failed to resolve default FileSystem. " << "n";
			return status::StatusInternal::DFS_ADAPTOR_IS_NOT_CONFIGURED;
		}

		// FileSystem is resolved. Proceed with updated file system descriptor
	}

	boost::mutex::scoped_lock lockconn(m_connmux);
	if (m_filesystems[fsDescriptor.dfs_type].count(fsDescriptor.host)) {
		// descriptor is already a part of the registry, nothing to add
		return status::StatusInternal::OK;
	}
	// create the FileSystem-bound descriptor and assign the File System adaptor to it
	boost::shared_ptr<FileSystemDescriptorBound> descriptor(
			fsDescriptor.dfs_type == DFS_TYPE::tachyon ?
					new TachyonFileSystemDescriptorBound(fsDescriptor) :
					new FileSystemDescriptorBound(fsDescriptor));
	// and insert new {key-value} under the appropriate FileSystem type
	m_filesystems[fsDescriptor.dfs_type].insert(
			std::make_pair(fsDescriptor.host, descriptor));
	return status::StatusInternal::OK;
}

const boost::shared_ptr<FileSystemDescriptorBound>* CacheLayerRegistry::getFileSystemDescriptor(const FileSystemDescriptor & fsDescriptor){
		boost::mutex::scoped_lock lock(m_connmux);
          if(m_filesystems.count(fsDescriptor.dfs_type) > 0 && m_filesystems[fsDescriptor.dfs_type].count(fsDescriptor.host) > 0){
        	  return &(m_filesystems[fsDescriptor.dfs_type][fsDescriptor.host]);
          }
          return nullptr;
	}

bool CacheLayerRegistry::findFile(const char* path, const FileSystemDescriptor& descriptor,
		managed_file::File*& file, const std::string transformCmd){
	std::string fqp = managed_file::File::constructLocalPath(descriptor, path);
	if(fqp.empty())
		return false;
	file = m_cache->find(fqp, transformCmd);
	return file != nullptr;
}

bool CacheLayerRegistry::findFile(const char* path, managed_file::File*& file){
	std::string fqp = std::string(path);
	if(fqp.empty())
		return false;
	file = m_cache->find(fqp);
	return file != nullptr;
}

bool CacheLayerRegistry::addFile(const char* path, const FileSystemDescriptor& descriptor, managed_file::File*& file,
	managed_file::NatureFlag creationFlag)
{
	std::string fqp = managed_file::File::constructLocalPath(descriptor, path);
	if(fqp.empty())
		return false;

	return m_cache->add(fqp, file, creationFlag);
}

bool CacheLayerRegistry::deleteFile(const FileSystemDescriptor &descriptor, const char* path, bool physically){
	std::string fqp = managed_file::File::constructLocalPath(descriptor, path);
	if(fqp.empty()){
		LOG (WARNING) << "Cache Layer Registry : file was not deleted. Unable construct fqp from \"" << path << "\"\n";
		return false;
	}
	// Below instruction will drop the file from file system - in case if there's no usage of that file so far.
	// If any pending users, the file won't be removed
	return m_cache->remove(std::string(fqp), physically);
}

bool CacheLayerRegistry::deletePath(const FileSystemDescriptor &descriptor, const char* path){
	std::string fqp = managed_file::File::constructLocalPath(descriptor, path);
	if(fqp.empty()){
		LOG (WARNING) << "Cache Layer Registry : path was not deleted. Unable construct fqp from \"" << path << "\"\n";
		return false;
	}
	// Below instruction will remove the path from file system - in case if there's no usage of its content so far.
	// If any pending users on some files, the overall operation status will be "false"
	return m_cache->deletePath(std::string(fqp));
}

bool CacheLayerRegistry::registerCreateFromSelectScenario(const dfsFile& local, const dfsFile& remote){
	boost::mutex::scoped_lock lockconn(m_createfromselect_mux);
    // if no scenario for file specified exists, add one.
	if (m_createFromSelect.find(local) == m_createFromSelect.end() ) {
		m_createFromSelect[local] = remote;
		return true;
	}
	return false;
}

bool CacheLayerRegistry::unregisterCreateFromSelectScenario(const dfsFile& local){
	boost::mutex::scoped_lock lockconn(m_createfromselect_mux);
	size_t erased_items = m_createFromSelect.erase(local);
	return erased_items == 1 ? true : false;
}

dfsFile CacheLayerRegistry::getCreateFromSelectScenario(const dfsFile& local, bool& exists){
	boost::mutex::scoped_lock lockconn(m_createfromselect_mux);
	itCreateFromSelect it = m_createFromSelect.find(local);
	if (it == m_createFromSelect.end()) {
		exists = false;
		return NULL;
	}
	exists = true;
	return (*it).second;
}
}

#endif /* CACHE_LAYER_REGISTRY_CC_ */
