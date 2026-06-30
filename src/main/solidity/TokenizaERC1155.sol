// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * TokenizaERC1155 — contrato ERC-1155 (multi-token) com mint, burn e URI configurável.
 * Construtor: (string uri, address owner)
 */
contract TokenizaERC1155 {

    string  public uri;
    address public owner;
    bool    public paused;

    mapping(uint256 => mapping(address => uint256)) private _balances;
    mapping(address => mapping(address => bool))    private _operatorApprovals;

    event TransferSingle(address indexed operator, address indexed from, address indexed to, uint256 id, uint256 value);
    event TransferBatch(address indexed operator, address indexed from, address indexed to, uint256[] ids, uint256[] values);
    event ApprovalForAll(address indexed account, address indexed operator, bool approved);
    event URI(string value, uint256 indexed id);
    event Paused(address account);
    event Unpaused(address account);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    modifier onlyOwner() {
        require(msg.sender == owner, "ERC1155: not owner");
        _;
    }

    modifier whenNotPaused() {
        require(!paused, "ERC1155: paused");
        _;
    }

    constructor(string memory _uri, address _owner) {
        uri   = _uri;
        owner = _owner;
    }

    function balanceOf(address account, uint256 id) external view returns (uint256) {
        return _balances[id][account];
    }

    function balanceOfBatch(address[] calldata accounts, uint256[] calldata ids)
        external view returns (uint256[] memory)
    {
        require(accounts.length == ids.length, "ERC1155: length mismatch");
        uint256[] memory batchBalances = new uint256[](accounts.length);
        for (uint256 i = 0; i < accounts.length; i++) {
            batchBalances[i] = _balances[ids[i]][accounts[i]];
        }
        return batchBalances;
    }

    function setApprovalForAll(address operator, bool approved) external {
        _operatorApprovals[msg.sender][operator] = approved;
        emit ApprovalForAll(msg.sender, operator, approved);
    }

    function isApprovedForAll(address account, address operator) external view returns (bool) {
        return _operatorApprovals[account][operator];
    }

    function safeTransferFrom(address from, address to, uint256 id, uint256 amount, bytes calldata data)
        external whenNotPaused
    {
        require(msg.sender == from || _operatorApprovals[from][msg.sender], "ERC1155: not approved");
        require(_balances[id][from] >= amount, "ERC1155: insufficient balance");
        _balances[id][from] -= amount;
        _balances[id][to]   += amount;
        emit TransferSingle(msg.sender, from, to, id, amount);
    }

    function mint(address to, uint256 id, uint256 amount) external onlyOwner whenNotPaused {
        _balances[id][to] += amount;
        emit TransferSingle(msg.sender, address(0), to, id, amount);
    }

    function mintBatch(address to, uint256[] calldata ids, uint256[] calldata amounts)
        external onlyOwner whenNotPaused
    {
        require(ids.length == amounts.length, "ERC1155: length mismatch");
        for (uint256 i = 0; i < ids.length; i++) {
            _balances[ids[i]][to] += amounts[i];
        }
        emit TransferBatch(msg.sender, address(0), to, ids, amounts);
    }

    function burn(address from, uint256 id, uint256 amount) external whenNotPaused {
        require(msg.sender == from || _operatorApprovals[from][msg.sender], "ERC1155: not approved");
        require(_balances[id][from] >= amount, "ERC1155: insufficient balance");
        _balances[id][from] -= amount;
        emit TransferSingle(msg.sender, from, address(0), id, amount);
    }

    function pause() external onlyOwner {
        paused = true;
        emit Paused(msg.sender);
    }

    function unpause() external onlyOwner {
        paused = false;
        emit Unpaused(msg.sender);
    }

    function setUri(string memory _uri) external onlyOwner {
        uri = _uri;
    }

    function transferOwnership(address newOwner) external onlyOwner {
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }
}
