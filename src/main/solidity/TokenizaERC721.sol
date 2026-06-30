// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * TokenizaERC721 — contrato ERC-721 (NFT) com mint, burn e baseURI configurável.
 * Construtor: (string name, string symbol, string baseUri, address owner)
 */
contract TokenizaERC721 {

    string  public name;
    string  public symbol;
    string  public baseUri;
    address public owner;
    bool    public paused;

    uint256 private _nextTokenId;

    mapping(uint256 => address) private _owners;
    mapping(address => uint256) private _balances;
    mapping(uint256 => address) private _tokenApprovals;
    mapping(address => mapping(address => bool)) private _operatorApprovals;

    event Transfer(address indexed from, address indexed to, uint256 indexed tokenId);
    event Approval(address indexed owner, address indexed approved, uint256 indexed tokenId);
    event ApprovalForAll(address indexed owner, address indexed operator, bool approved);
    event Paused(address account);
    event Unpaused(address account);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "ERC721: not owner");
        _;
    }

    modifier whenNotPaused() {
        require(!paused, "ERC721: paused");
        _;
    }

    constructor(
        string memory _name,
        string memory _symbol,
        string memory _baseUri,
        address _owner
    ) {
        name    = _name;
        symbol  = _symbol;
        baseUri = _baseUri;
        owner   = _owner;
    }

    function balanceOf(address account) external view returns (uint256) {
        return _balances[account];
    }

    function ownerOf(uint256 tokenId) external view returns (address) {
        address tokenOwner = _owners[tokenId];
        require(tokenOwner != address(0), "ERC721: nonexistent token");
        return tokenOwner;
    }

    function tokenURI(uint256 tokenId) external view returns (string memory) {
        require(_owners[tokenId] != address(0), "ERC721: nonexistent token");
        return string(abi.encodePacked(baseUri, _toString(tokenId)));
    }

    function approve(address to, uint256 tokenId) external {
        address tokenOwner = _owners[tokenId];
        require(msg.sender == tokenOwner || _operatorApprovals[tokenOwner][msg.sender], "ERC721: not approved");
        _tokenApprovals[tokenId] = to;
        emit Approval(tokenOwner, to, tokenId);
    }

    function setApprovalForAll(address operator, bool approved) external {
        _operatorApprovals[msg.sender][operator] = approved;
        emit ApprovalForAll(msg.sender, operator, approved);
    }

    function transferFrom(address from, address to, uint256 tokenId) external whenNotPaused {
        require(_isApprovedOrOwner(msg.sender, tokenId), "ERC721: not approved");
        _transfer(from, to, tokenId);
    }

    function mint(address to) external onlyOwner whenNotPaused returns (uint256) {
        uint256 tokenId = _nextTokenId++;
        _owners[tokenId]   = to;
        _balances[to]      += 1;
        emit Transfer(address(0), to, tokenId);
        return tokenId;
    }

    function burn(uint256 tokenId) external whenNotPaused {
        address tokenOwner = _owners[tokenId];
        require(_isApprovedOrOwner(msg.sender, tokenId), "ERC721: not approved");
        _balances[tokenOwner] -= 1;
        delete _owners[tokenId];
        delete _tokenApprovals[tokenId];
        emit Transfer(tokenOwner, address(0), tokenId);
    }

    function pause() external onlyOwner {
        paused = true;
        emit Paused(msg.sender);
    }

    function unpause() external onlyOwner {
        paused = false;
        emit Unpaused(msg.sender);
    }

    function setBaseUri(string memory _baseUri) external onlyOwner {
        baseUri = _baseUri;
    }

    function transferOwnership(address newOwner) external onlyOwner {
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }

    function _transfer(address from, address to, uint256 tokenId) internal {
        require(_owners[tokenId] == from, "ERC721: wrong owner");
        _balances[from]          -= 1;
        _balances[to]            += 1;
        _owners[tokenId]          = to;
        delete _tokenApprovals[tokenId];
        emit Transfer(from, to, tokenId);
    }

    function _isApprovedOrOwner(address spender, uint256 tokenId) internal view returns (bool) {
        address tokenOwner = _owners[tokenId];
        return (spender == tokenOwner ||
                _tokenApprovals[tokenId] == spender ||
                _operatorApprovals[tokenOwner][spender]);
    }

    function _toString(uint256 value) internal pure returns (string memory) {
        if (value == 0) return "0";
        uint256 temp = value;
        uint256 digits;
        while (temp != 0) { digits++; temp /= 10; }
        bytes memory buffer = new bytes(digits);
        while (value != 0) { digits--; buffer[digits] = bytes1(uint8(48 + value % 10)); value /= 10; }
        return string(buffer);
    }
}
