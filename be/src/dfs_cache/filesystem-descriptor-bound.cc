/*
 * @file filesystem-descriptor-bound.cc
 * @brief implementation of hadoop FileSystem mediator (mainly types translator)
 * Specifics for FileSystem mediators should also be placed here (like for Tachyon)
 *
 * @date   Oct 10, 2014
 * @author elenav
 */
#include <fcntl.h>

#include "dfs_cache/filesystem-descriptor-bound.hpp"
#include "dfs_cache/hadoop-fs-adaptive.h"

namespace impala {

std::ostream& operator<<(std::ostream& out, const DFS_TYPE& value) {
	static std::map<DFS_TYPE, std::string> strings;
	if (strings.size() == 0) {
#define INSERT_ELEMENT(p) strings[p] = #p
		INSERT_ELEMENT(hdfs);
		INSERT_ELEMENT(s3n);
		INSERT_ELEMENT(s3a);
		INSERT_ELEMENT(local);
		INSERT_ELEMENT(tachyon);
		INSERT_ELEMENT(DEFAULT_FROM_CONFIG);
		INSERT_ELEMENT(OTHER);
		INSERT_ELEMENT(NON_SPECIFIED);
#undef INSERT_ELEMENT
	}
	return out << (value == DFS_TYPE::local ? "file" : strings[value]);
}

fsBridge FileSystemDescriptorBound::connect() {
	fsBuilder* fs_builder = _dfsNewBuilder();
	if (!m_fsDescriptor.host.empty()) {
		_dfsBuilderSetHostAndFilesystemType(fs_builder,	m_fsDescriptor.host.c_str(),
				m_fsDescriptor.dfs_type);
	} else {
		// Connect to local filesystem
		_dfsBuilderSetHost(fs_builder, NULL);
	}
	// forward the port to the unsigned builder's port only if the port is positive
	if(m_fsDescriptor.port > 0)
		_dfsBuilderSetPort(fs_builder, m_fsDescriptor.port);
	return _dfsBuilderConnect(fs_builder);
}

FileSystemDescriptorBound::~FileSystemDescriptorBound(){
	// Disconnect any conections we have to a target file system:
	for(auto item : m_connections){
		_dfsDisconnect(item->connection);
	}
}

int FileSystemDescriptorBound::resolveFsAddress(FileSystemDescriptor& fsDescriptor){
	int status = -1;
	// create the builder from descriptor
	fsBuilder* fs_builder = _dfsNewBuilder();
	// is there's host specified, set it:

	if (!fsDescriptor.host.empty())
		_dfsBuilderSetHost(fs_builder, fsDescriptor.host.c_str());
	else
		// Connect to local filesystem
		_dfsBuilderSetHost(fs_builder, NULL);

	// set the port:
	_dfsBuilderSetPort(fs_builder, fsDescriptor.port);

	// now get effective host, port and filesystem type from Hadoop FileSystem resolver:
	char host[HOST_NAME_MAX];
    status = _dfsGetDefaultFsHostPortType(host, sizeof(host), fs_builder, &fsDescriptor.port, &fsDescriptor.dfs_type);

    if(!status){
    	fsDescriptor.host = std::string(host);
    	// if port is not specified, set 0
    	fsDescriptor.port = fsDescriptor.port < 0 ? 0 : fsDescriptor.port;
    }
	return status;
}

raiiDfsConnection FileSystemDescriptorBound::getFreeConnection() {
	freeConnectionPredicate predicateFreeConnection;

	boost::mutex::scoped_lock(m_mux);
	std::list<boost::shared_ptr<dfsConnection> >::iterator i1;

	// First try to find the free connection:
	i1 = std::find_if(m_connections.begin(), m_connections.end(),
			predicateFreeConnection);
	if (i1 != m_connections.end()) {
		// return the connection, mark it busy!
		(*i1)->state = dfsConnection::BUSY_OK;
		return std::move(raiiDfsConnection(*i1));
	}

	// check any other connections except in "BUSY_OK" or "FREE_INITIALIZED" state.
	anyNonInitializedConnectionPredicate uninitializedPredicate;
	std::list<boost::shared_ptr<dfsConnection> >::iterator i2;

	i2 = std::find_if(m_connections.begin(), m_connections.end(),
			uninitializedPredicate);
	if (i2 != m_connections.end()) {
		// have ubnormal connections, get the first and reinitialize it:
		fsBridge conn = connect();
		if (conn != NULL) {
			LOG (INFO)<< "Existing non-initialized connection is initialized and will be used for file system \"" << m_fsDescriptor.dfs_type << ":"
			<< m_fsDescriptor.host << "\"" << "\n";
			(*i2)->connection = conn;
			(*i2)->state = dfsConnection::BUSY_OK;
			return std::move(raiiDfsConnection(*i2));
		}
		else
		return std::move(raiiDfsConnection(dfsConnectionPtr())); // no connection can be established. No retries right now.
	}

	// seems there're no unused connections right now.
	// need to create new connection to DFS:
	LOG (INFO)<< "No free connection exists for file system \"" << m_fsDescriptor.dfs_type << ":" << m_fsDescriptor.host << "\", going to create one." << "\n";
	boost::shared_ptr<dfsConnection> connection(new dfsConnection());
	connection->state = dfsConnection::NON_INITIALIZED;

	fsBridge conn = connect();
	if (conn != NULL) {
		connection->connection = conn;
		connection->state = dfsConnection::FREE_INITIALIZED;
		m_connections.push_back(connection);
		return getFreeConnection();
	}
	LOG (ERROR)<< "Unable to connect to file system \"." << "\"" << "\n";
	// unable to connect to DFS.
	return std::move(raiiDfsConnection(dfsConnectionPtr()));
}

dfsFile FileSystemDescriptorBound::fileOpen(raiiDfsConnection& conn, const char* path, int flags, int bufferSize,
		short replication, tSize blocksize){
	return _dfsOpenFile(conn.connection()->connection, path, flags, bufferSize, replication, blocksize);
}

int FileSystemDescriptorBound::fileClose(raiiDfsConnection& conn, dfsFile file){
	return _dfsCloseFile(conn.connection()->connection, file);
}

tOffset FileSystemDescriptorBound::fileTell(raiiDfsConnection& conn, dfsFile file){
    return _dfsTell(conn.connection()->connection, file);
}

int FileSystemDescriptorBound::fileSeek(raiiDfsConnection& conn, dfsFile file,
		tOffset desiredPos){
	return _dfsSeek(conn.connection()->connection, file, desiredPos);
}

tSize FileSystemDescriptorBound::fileRead(raiiDfsConnection& conn, dfsFile file,
		void* buffer, tSize length){
	return _dfsRead(conn.connection()->connection, file, buffer, length);
}

tSize FileSystemDescriptorBound::filePread(raiiDfsConnection& conn, dfsFile file, tOffset position,
		void* buffer, tSize length){
	return _dfsPread(conn.connection()->connection, file, position, buffer, length);
}

tSize FileSystemDescriptorBound::fileWrite(raiiDfsConnection& conn, dfsFile file, const void* buffer, tSize length){
	return _dfsWrite(conn.connection()->connection, file, buffer, length);
}

int FileSystemDescriptorBound::fileFlush(raiiDfsConnection& conn, dfsFile file){
	return _dfsFlush(conn.connection()->connection, file);
}

int FileSystemDescriptorBound::fileRename(raiiDfsConnection& conn, const char* oldPath, const char* newPath){
	return _dfsRename(conn.connection()->connection, oldPath, newPath);
}

int FileSystemDescriptorBound::pathDelete(raiiDfsConnection& conn, const char* path, int recursive){
	return _dfsDelete(conn.connection()->connection, path, recursive);
}

dfsFileInfo* FileSystemDescriptorBound::fileInfo(raiiDfsConnection& conn, const char* path){
	return _dfsGetPathInfo(conn.connection()->connection, path);
}

dfsFileInfo* FileSystemDescriptorBound::listDirectory(raiiDfsConnection& conn, const char* path, int *numEntries){
	return _dfsListDirectory(conn.connection()->connection, path, numEntries);
}

int FileSystemDescriptorBound::createDirectory(raiiDfsConnection& conn, const char* path){
	return _dfsCreateDirectory(conn.connection()->connection, path);
}

void FileSystemDescriptorBound::freeFileInfo(dfsFileInfo* fileInfo, int numOfEntries){
	return _dfsFreeFileInfo(fileInfo, numOfEntries);
}

bool FileSystemDescriptorBound::pathExists(raiiDfsConnection& conn, const char* path){
	return (_dfsPathExists(conn.connection()->connection, path) == 0 ? true : false);
}

int FileSystemDescriptorBound::fileCopy(raiiDfsConnection& conn_src, const char* src, raiiDfsConnection& conn_dest, const char* dst){
	return _dfsCopy(conn_src.connection()->connection, src, conn_dest.connection()->connection, dst);
}

int FileSystemDescriptorBound::fsMove(raiiDfsConnection& conn_src, const char* src, raiiDfsConnection& conn_dest, const char* dst){
	return _dfsMove(conn_src.connection()->connection, src, conn_dest.connection()->connection, dst);
}

int64_t FileSystemDescriptorBound::getDefaultBlockSize(raiiDfsConnection& conn){
	return _dfsGetDefaultBlockSize(conn.connection()->connection);
}

int FileSystemDescriptorBound::fileAvailable(raiiDfsConnection& conn, dfsFile file){
	return _dfsAvailable(conn.connection()->connection, file);
}

int FileSystemDescriptorBound::fsSetReplication(raiiDfsConnection& conn, const char* path, int16_t replication){
	return _dfsSetReplication(conn.connection()->connection, path, replication);
}

tOffset FileSystemDescriptorBound::fsGetCapacity(raiiDfsConnection& conn){
	return _dfsGetCapacity(conn.connection()->connection);
}

tOffset FileSystemDescriptorBound::fsGetUsed(raiiDfsConnection& conn){
	return _dfsGetUsed(conn.connection()->connection);
}

int FileSystemDescriptorBound::fsChown(raiiDfsConnection& conn, const char* path, const char *owner,
		const char *group){
	return _dfsChown(conn.connection()->connection, path, owner, group);
}

int FileSystemDescriptorBound::fsChmod(raiiDfsConnection& conn, const char* path, short mode){
	return _dfsChmod(conn.connection()->connection, path, mode);
}

struct hadoopRzOptions* FileSystemDescriptorBound::_hadoopRzOptionsAlloc(void){
	return hadoopRzOptionsAlloc();
}

int FileSystemDescriptorBound::_hadoopRzOptionsSetSkipChecksum(struct hadoopRzOptions* opts, int skip){
	return hadoopRzOptionsSetSkipChecksum(opts, skip);
}

int FileSystemDescriptorBound::_hadoopRzOptionsSetByteBufferPool(
	        struct hadoopRzOptions* opts, const char *className){
	return hadoopRzOptionsSetByteBufferPool(opts, className);
}

void FileSystemDescriptorBound::_hadoopRzOptionsFree(struct hadoopRzOptions* opts){
	hadoopRzOptionsFree(opts);
}

struct hadoopRzBuffer* FileSystemDescriptorBound::_hadoopReadZero(dfsFile file,
	        struct hadoopRzOptions* opts, int32_t maxLength){
	return hadoopReadZero(file, opts, maxLength);
}

int32_t FileSystemDescriptorBound::_hadoopRzBufferLength(const struct hadoopRzBuffer* buffer){
	return hadoopRzBufferLength(buffer);
}

const void * FileSystemDescriptorBound::_hadoopRzBufferGet(const struct hadoopRzBuffer* buffer){
	return hadoopRzBufferGet(buffer);
}

void FileSystemDescriptorBound::_hadoopRzBufferFree(dfsFile file, struct hadoopRzBuffer* buffer){
	hadoopRzBufferFree(file, buffer);
}

dfsFile TachyonFileSystemDescriptorBound::fileOpen(raiiDfsConnection& conn, const char* path, int flags, int bufferSize,
		short replication, tSize blocksize){
	dfsFile handle = _dfsOpenFile(conn.connection()->connection, path, flags, bufferSize, replication, blocksize);
	if(handle == NULL){
		LOG(ERROR) << "Tachyon file system descriptor failed to open file with path \"" <<
				path << "\". Null handle will be returned. \n";
		return handle;
	}
    if(flags == O_WRONLY){
    	// file is opened for write, no need to trigger its caching on Tachyon, just reply it:
    	return handle;
    }
	// read from the remote file to trigger its caching
	tSize last_read = 0;
	tSize bytes = 0;

	const int BUFFER_SIZE = 6684672;
	char* buffer = (char*)malloc(sizeof(char) * BUFFER_SIZE);
	if(buffer == NULL){
		LOG (ERROR)<< "Insufficient memory to allocate buffer for read the file \"" <<  path <<
				"\" on filesystem " << m_fsDescriptor.dfs_type << ":" << m_fsDescriptor.host << "\"" << "\n";
		// close the handle, there're scenarios where Impala cannot work stable with non-cached Tachyon stream
		// (in particular, file seek)
		_dfsCloseFile(conn.connection()->connection, handle);
		return NULL;
	}

	// define a reader
	boost::function<void ()> reader = [&]() {
		last_read = _dfsRead(conn.connection()->connection, handle, (void*)buffer, BUFFER_SIZE);
		for (; last_read > 0;) {
			bytes += last_read;
			// read next data buffer:
			last_read = _dfsRead(conn.connection()->connection, handle, (void*)buffer, BUFFER_SIZE);
		}
	};
	// and run the reader
	reader();
	if(last_read == 0){
		free(buffer);

		// file is read to end, close the stream (this will trigger Tachyon to cache the file in memory)
		// and return reopened stream on top:
		int ret = 0;
		ret = _dfsCloseFile(conn.connection()->connection, handle);
		if(ret){
			LOG(ERROR) << "Tachyon file system descriptor failed to finalize file caching for path \"" <<
					path << "\". Null handle will be returned. \n";
			return NULL;
		}
        // reopen the stream from position 0:
        handle = _dfsOpenFile(conn.connection()->connection, path, flags, bufferSize, replication, blocksize);
        return handle;
	}
	if(last_read == -1){
		LOG (WARNING) << "Remote file \"" << path << "\" read encountered IO exception." << "\n";
		// Note that retry mechanism may be inserted here, just to be sure retry should try to re-read the file
		// from position 0, otherwise caching will be cancelled by Tachyon
	}
	return NULL;
}

} /** namespace impala */

